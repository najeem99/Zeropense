package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.jsTrim
import java.util.Locale

private val OPERATOR_PREFIX = rx("^[A-Z]{2}-", "")
private val OPERATOR_SUFFIX = rx("-[A-Z]$", "")

/**
 * One bank sender under its different operator prefixes/suffixes:
 * "VM-HDFCBK", "AD-HDFCBK-S" and "HDFCBK" are all "HDFCBK".
 */
fun senderKey(sender: String): String = sender.jsTrim().uppercase(Locale.ROOT).replace(OPERATOR_PREFIX, "").replace(OPERATOR_SUFFIX, "")

private const val TEMPLATE_WORDS = 8

/** Upper-case words that are part of a bank's wording, not a merchant or name. */
private val KEEP_CAPS = setOf(
    "AED", "INR", "RS", "UPI", "ATM", "POS", "OTP", "NEFT", "IMPS", "RTGS", "EMI", "SMS", "ID", "PIN",
    "IBAN", "VPA", "AC", "CR", "DR", "UAE", "ENBD", "ADCB", "FAB", "HDFC", "ICICI", "SBI"
)

private val NUMBER = rx("\\d[\\d,]*(?:\\.\\d+)?", "")
private val NOT_WORD = rx("[^A-Za-z#]+", "")
private val TWO_CAPS = rx("[A-Z]{2}", "")

private fun isCaps(w: String) = TWO_CAPS.containsMatchIn(w) && w == w.uppercase(Locale.ROOT)

/**
 * The fixed opening of a message with numbers and merchant names masked, used to group one
 * sender's SMS into kinds: "Purchase amount of AED 12.00 at GLOBALFASHION.AE on…" →
 * "purchase amount of aed # at * on". Merchants are spotted as upper-case words in an
 * otherwise mixed-case message; all-caps messages are left as they are.
 */
fun templateKey(body: String): String {
    val words = body.replace(NUMBER, "#").split(NOT_WORD).filter { it.isNotEmpty() }
    val lettered = words.filter { w -> w.any { it != '#' } }
    val maskCaps = lettered.count(::isCaps).toDouble() / maxOf(1, lettered.size) < 0.5

    val out = ArrayList<String>()
    for (w in words) {
        val t = if (maskCaps && isCaps(w) && w.replace("#", "") !in KEEP_CAPS) "*" else w.lowercase(Locale.ROOT)
        if (t == "*" && out.lastOrNull() == "*") continue
        out.add(t)
        if (out.size == TEMPLATE_WORDS) break
    }
    return out.joinToString(" ")
}

enum class RuleAction(val db: String) {
    /** These kinds become transactions. */
    IMPORT("import"),

    /** Known non-transactions (OTP, promos). */
    IGNORE("ignore");

    companion object {
        fun from(value: String): RuleAction = if (value == "ignore") IGNORE else IMPORT
    }
}

/** A message kind the user configured during SMS setup. */
data class SmsRule(
    val id: String,
    val senderKey: String,
    val templateKey: String,
    val action: RuleAction,
    /** Which amount in the message is the transaction, by position. */
    val amountIndex: Int,
    val direction: Direction,
    val currency: Currency,
    /** For kinds that never name a card: the account to use. */
    val fixedAccountId: String?,
    val sample: String
)

fun ruleFor(sms: RawSms, rules: List<SmsRule>): SmsRule? {
    val s = senderKey(sms.sender)
    val t = templateKey(sms.body)
    return rules.firstOrNull { it.senderKey == s && it.templateKey == t }
}

private fun cardDigits(body: String): String? = findAccount(body) ?: findDigitsCandidates(body).firstOrNull()?.digits?.takeLast(4)

/** Reads a message with an 'import' rule. Position-based, so new merchants and amounts still work. */
fun applyRule(rule: SmsRule, sms: RawSms): ParsedSms? {
    if (rule.action != RuleAction.IMPORT) return null
    val amount = findAmountCandidates(sms.body).getOrNull(rule.amountIndex) ?: return null
    return ParsedSms(
        amountMinor = amount.minor,
        currency = amount.currency ?: rule.currency,
        direction = rule.direction,
        merchant = findMerchant(sms.body, rule.direction),
        accountHint = cardDigits(sms.body),
        occurredAt = findDate(sms.body),
        parserId = "rule:${rule.id}"
    )
}

enum class KindLabel(val text: String) {
    TRANSACTION("Transaction"),
    OTP("OTP"),
    PROMO("Promotional"),
    REMINDER("Reminder"),
    REQUEST("Payment request"),
    FAILED("Failed / declined"),
    MONEY("Has an amount"),
    OTHER("Other")
}

private val REASON_LABELS = mapOf(
    "OTP / verification code" to KindLabel.OTP,
    "Promotional" to KindLabel.PROMO,
    "Upcoming debit reminder" to KindLabel.REMINDER,
    "Payment request, not a payment" to KindLabel.REQUEST,
    "Failed or declined" to KindLabel.FAILED
)

data class KindSuggestion(val label: KindLabel, val amountIndex: Int, val direction: Direction, val currency: Currency)

/** Guesses what a kind of message is from one sample, to pre-fill the setup. */
fun suggestKind(sample: RawSms): KindSuggestion {
    val amounts = findAmountCandidates(sample.body)
    val firstReal = maxOf(0, amounts.indexOfFirst { !it.looksLikeBalance })
    val fallback = KindSuggestion(
        label = if (amounts.isNotEmpty()) KindLabel.MONEY else KindLabel.OTHER,
        amountIndex = firstReal,
        direction = findDirection(sample.body) ?: Direction.DEBIT,
        currency = amounts.getOrNull(firstReal)?.currency ?: Currency.AED
    )

    val reason = skipReason(sample.body)
    if (reason != null) return fallback.copy(label = REASON_LABELS[reason] ?: KindLabel.OTHER)

    val parsed = parseSms(sample, emptyList()) ?: return fallback
    val index = amounts.indexOfFirst { it.minor == parsed.amountMinor }
    return KindSuggestion(
        label = KindLabel.TRANSACTION,
        amountIndex = if (index == -1) firstReal else index,
        direction = parsed.direction,
        currency = parsed.currency
    )
}

data class MessageKind(
    val templateKey: String,
    val messages: List<RawSms>,
    val suggestion: KindSuggestion,
    /** Distinct last-4 card/account digits seen across the kind's messages. */
    val digits: List<String>
)

data class SenderGroup(
    val senderKey: String,
    /** Raw addresses as the phone stores them, for reading the inbox by sender. */
    val addresses: List<String>,
    val kinds: List<MessageKind>
)

/** Groups messages by sender, then by kind (largest first). */
fun groupMessages(messages: List<RawSms>): List<SenderGroup> {
    class Sender {
        val addresses = LinkedHashSet<String>()
        val kinds = LinkedHashMap<String, MutableList<RawSms>>()
    }
    val senders = LinkedHashMap<String, Sender>()
    for (m in messages) {
        val s = senders.getOrPut(senderKey(m.sender)) { Sender() }
        s.addresses.add(m.sender)
        s.kinds.getOrPut(templateKey(m.body)) { ArrayList() }.add(m)
    }
    return senders.map { (sk, s) ->
        SenderGroup(
            senderKey = sk,
            addresses = s.addresses.toList(),
            kinds = s.kinds.map { (tk, msgs) ->
                // The newest message is the most representative sample of the current format.
                MessageKind(tk, msgs, suggestKind(msgs.last()), msgs.mapNotNull { cardDigits(it.body) }.distinct())
            }.sortedByDescending { it.messages.size }
        )
    }
}
