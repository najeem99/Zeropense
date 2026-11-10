package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.normalizeCurrency
import com.najeemfasil.expense.core.parseAmountToMinor
import com.najeemfasil.expense.core.parseSmsDate

/** Extra patterns a bank profile can try before the generic ones. Group 1 is the value. */
interface ExtractHints {
    val defaultCurrency: Currency? get() = null

    /** Patterns whose group 1 is an amount with no currency next to it, e.g. SBI "debited by 150.0". */
    val amount: List<Regex> get() = emptyList()
    val merchant: List<Regex> get() = emptyList()
}

private object NoHints : ExtractHints

private const val NUM = "(\\d[\\d,]*(?:\\.\\d{1,2})?)"
private const val CUR = "(INR|Rs\\.?|₹|AED|Dhs?)"
private val AMOUNT_RE = rx("$CUR\\s*$NUM|$NUM\\s*(INR|AED)\\b")

/** Words that, just before an amount, mean it is a balance or limit, not the transaction. */
private val NOT_TXN_AMOUNT = rx("(bal(ance)?|limit|avl|available|outstanding|due)[\\s.:a-z]{0,12}$")

private val DEBIT_WORDS = rx("\\b(debited|spent|withdrawn|paid|purchase|sent|deducted|debit)\\b")
private val CREDIT_WORDS = rx("\\b(credited|received|deposited|refund(ed)?|reversed|reversal|cashback|credit(?!\\s*card))\\b")

/** A refund or reversal is money back even when the message opens with the purchase. */
private val REFUND_WORDS = rx("\\b(refund(ed)?|reversed|reversal)\\b")

private const val STOP = "(?=\\s+(?:on|via|ref|refno|txn|using|for|from|is|was|dated|avl|upi|not|\\()\\b|[,;\\n]|\\.(?=\\s|$)|$)"
private const val NAME = "([A-Za-z][A-Za-z0-9 &.'@_\\-*/]{1,40}?)"

/** "Merchant: AMRK AND SAVE," / "Location - LULU" style banks that label the field. */
private val LABELED_MERCHANT = rx("\\b(?:merchant(?:\\s+name)?|location|payee|beneficiary|at)\\s*[:\\-]\\s*([A-Za-z][^,;\\n]{1,40}?)(?=\\s*(?:[,;\\n]|\\.\\s|\\.$|$))")
private val INFO_MERCHANT = rx("\\bInfo:?\\s*([A-Za-z][^.\\n]{1,40})")
private val DEBIT_MERCHANT = listOf(
    LABELED_MERCHANT,
    rx("\\b(?:at|@)\\s+$NAME$STOP"),
    rx("\\b(?:to|towards)\\s+(?:VPA\\s+)?$NAME$STOP"),
    INFO_MERCHANT
)
private val CREDIT_MERCHANT = listOf(
    LABELED_MERCHANT,
    rx("\\b(?:from|by)\\s+(?:VPA\\s+)?$NAME$STOP"),
    INFO_MERCHANT
)

/** Captures that look like our own account, not a counterparty. */
private val SELF_REF = rx("^(your|you|a\\/c|ac|acct|account|card|xx)\\b")

private val ACCOUNT_RE = listOf(
    rx("\\b(?:a\\/c|acct|account|ac|card)\\b\\.?\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:with|in)?\\s*)?[:\\-]?\\s*[x*]*\\s*(\\d{3,6})\\b"),
    rx("\\b[x*]{2,}(\\d{3,6})\\b")
)

private val DATE_RE = rx(
    "\\b(\\d{4}-\\d{2}-\\d{2}(?:[ T:]\\d{1,2}:\\d{2}(?::\\d{2})?)?|\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}(?:[ ,]+\\d{1,2}:\\d{2}(?::\\d{2})?)?|\\d{1,2}[-\\s]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-\\s,]?\\d{2,4})"
)

private class Money(val minor: Long, val currency: Currency)

private fun findAmount(body: String, hints: ExtractHints): Money? {
    for (m in AMOUNT_RE.findAll(body)) {
        val index = m.range.first
        if (NOT_TXN_AMOUNT.containsMatchIn(body.substring(maxOf(0, index - 20), index))) continue
        val currency = normalizeCurrency(m.g(1) ?: m.g(4).orEmpty())
        val minor = parseAmountToMinor(m.g(2) ?: m.g(3).orEmpty())
        if (currency != null && minor != null) return Money(minor, currency)
    }
    for (re in hints.amount) {
        val minor = re.find(body)?.g(1)?.let(::parseAmountToMinor)
        if (minor != null) return Money(minor, hints.defaultCurrency ?: Currency.INR)
    }
    return null
}

private fun Regex.search(body: String): Int = find(body)?.range?.first ?: -1

fun findDirection(body: String): Direction? {
    if (REFUND_WORDS.containsMatchIn(body)) return Direction.CREDIT
    val d = DEBIT_WORDS.search(body)
    val c = CREDIT_WORDS.search(body)
    if (d == -1 && c == -1) return null
    if (c == -1) return Direction.DEBIT
    if (d == -1) return Direction.CREDIT
    // "debited from your a/c ... credited to SWIGGY" is a debit: the first verb wins.
    return if (d < c) Direction.DEBIT else Direction.CREDIT
}

private val TRAILING_PUNCTUATION = rx("[\\s.,;:\\-*/]+$", "")

fun cleanMerchant(raw: String): String? {
    val s = raw.replace(SPACES, " ").replace(TRAILING_PUNCTUATION, "").jsTrim()
    if (s.length < 2 || SELF_REF.containsMatchIn(s)) return null
    return s
}

fun findMerchant(body: String, direction: Direction, hints: ExtractHints? = null): String? {
    val generic = if (direction == Direction.DEBIT) DEBIT_MERCHANT else CREDIT_MERCHANT
    for (re in hints?.merchant.orEmpty() + generic) {
        val name = re.find(body)?.g(1)?.let(::cleanMerchant)
        if (name != null) return name
    }
    return null
}

private val SNIPPET_NUMBERS = rx("(?:INR|Rs\\.?|₹|AED|Dhs?)?\\s*\\d[\\d,.*/:-]*")

/**
 * A short label for a transaction whose merchant wasn't found: the counterparty if either
 * direction's patterns find one, else the message's opening words without numbers.
 */
fun smsSnippet(body: String): String {
    val name = findMerchant(body, Direction.DEBIT) ?: findMerchant(body, Direction.CREDIT)
    if (name != null) return name
    return body
        .replace(SPACES, " ")
        .replace(SNIPPET_NUMBERS, " ")
        .replace(SPACES, " ")
        .jsTrim()
        .split(' ')
        .take(6)
        .joinToString(" ")
}

private val SALARY_WORDS = rx("\\b(salary|payroll|wps)\\b")
private val TRANSFER_IN_WORDS = rx("\\b(transfer(red)?|remittance|received|aani|imps|neft|rtgs|upi|ipi)\\b")

/** For money coming in: which default income category it belongs to, if it's clear. */
fun incomeCategory(body: String): String? = when {
    SALARY_WORDS.containsMatchIn(body) -> "salary"
    REFUND_WORDS.containsMatchIn(body) -> "refund"
    TRANSFER_IN_WORDS.containsMatchIn(body) -> "transfer-in"
    else -> null
}

/** A name for income messages that name no sender, e.g. "Salary Deposited". */
fun incomeLabel(body: String): String? = when {
    SALARY_WORDS.containsMatchIn(body) -> "Salary"
    REFUND_WORDS.containsMatchIn(body) -> "Refund"
    else -> null
}

fun findAccount(body: String): String? {
    for (re in ACCOUNT_RE) {
        val hit = re.find(body)?.g(1)
        if (hit != null) return hit.takeLast(4)
    }
    return null
}

fun findDate(body: String): Long? = DATE_RE.find(body)?.g(1)?.let(::parseSmsDate)

/** Keyword-and-pattern extraction shared by the generic parser and bank profiles. */
fun extract(body: String, parserId: String, hints: ExtractHints? = null): ParsedSms? {
    val amount = findAmount(body, hints ?: NoHints) ?: return null
    val direction = findDirection(body) ?: return null
    return ParsedSms(
        amountMinor = amount.minor,
        currency = amount.currency,
        direction = direction,
        merchant = findMerchant(body, direction, hints),
        accountHint = findAccount(body),
        occurredAt = findDate(body),
        parserId = parserId
    )
}
