package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.hashString
import com.najeemfasil.expense.core.isSameDay
import com.najeemfasil.expense.feature.categories.MerchantRule
import com.najeemfasil.expense.feature.categories.suggestCategory
import com.najeemfasil.expense.feature.transactions.DedupeCandidate
import com.najeemfasil.expense.feature.transactions.DedupeIndex
import java.util.Locale

data class AccountRef(val id: String, val currency: Currency, val smsHint: String?)

class IngestContext(
    /** Message kinds configured in SMS setup. Only these and custom parsers are imported. */
    val smsRules: List<SmsRule>,
    val customParsers: List<CustomParser>,
    val accounts: List<AccountRef>,
    /** Existing transactions, for duplicate detection. New drafts are added to it. */
    val dedupe: DedupeIndex,
    val rules: List<MerchantRule>
)

/** A transaction ready to be saved (pending in Review, or accepted by SMS setup). */
data class TransactionDraft(
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val merchant: String?,
    val occurredAt: Long,
    val accountId: String?,
    /** Last digits of the card/account named in the SMS, kept so unknown cards can be added later. */
    val accountHint: String?,
    val categoryId: String?,
    val duplicateOfId: String?,
    val smsHash: String,
    val smsSender: String,
    val smsBody: String,
    val parserId: String
)

sealed interface IngestResult {
    /** Not a configured kind of message. Nothing is stored. */
    data class Ignored(val reason: String) : IngestResult

    /** From a configured bank sender and has an amount, but no configured kind matched: worth setting up. */
    data object Unparsed : IngestResult

    data class Draft(val draft: TransactionDraft) : IngestResult

    /** The same SMS was imported before (including rejected ones). */
    data object AlreadyImported : IngestResult
}

fun smsHash(sms: RawSms): String = hashString("${sms.sender}|${sms.receivedAt}|${sms.body}")

/**
 * The account whose last digits appear in the SMS. Digits that match no account mean a
 * card the user hasn't added yet, so stay unlinked. Without digits, fall back to the
 * only account in that currency.
 */
fun matchAccount(hint: String?, currency: Currency, accounts: List<AccountRef>): String? {
    if (!hint.isNullOrEmpty()) {
        return accounts.firstOrNull { !it.smsHint.isNullOrEmpty() && hint.takeLast(4) == it.smsHint.takeLast(4) }?.id
    }
    return accounts.filter { it.currency == currency }.singleOrNull()?.id
}

private fun isConfiguredSender(sms: RawSms, ctx: IngestContext): Boolean {
    val key = senderKey(sms.sender)
    val lower = sms.sender.lowercase(Locale.ROOT)
    return ctx.smsRules.any { it.senderKey == key } ||
        ctx.customParsers.any { !it.senderContains.isNullOrEmpty() && lower.contains(it.senderContains.lowercase(Locale.ROOT)) }
}

/**
 * Pure decision step. Only message kinds the user configured (SMS setup rules, then custom
 * parsers) become transactions; everything else is ignored.
 */
fun processSms(sms: RawSms, ctx: IngestContext): IngestResult {
    var parsed: ParsedSms? = null
    var fixedAccountId: String? = null

    val rule = ruleFor(sms, ctx.smsRules)
    if (rule != null) {
        if (rule.action == RuleAction.IGNORE) return IngestResult.Ignored("You chose to ignore this kind of message")
        parsed = applyRule(rule, sms)
        fixedAccountId = rule.fixedAccountId
    }
    if (parsed == null) {
        for (p in ctx.customParsers) {
            parsed = applyCustomParser(p, sms)
            if (parsed != null) break
        }
    }
    if (parsed == null) {
        val configured = isConfiguredSender(sms, ctx)
        if (configured && skipReason(sms.body) == null && findAmountCandidates(sms.body).isNotEmpty()) return IngestResult.Unparsed
        return IngestResult.Ignored(if (configured) "Not a transaction" else "Not a configured bank SMS")
    }

    // SMS dates rarely carry a time; the receive time is more precise on the same day,
    // and duplicate detection depends on it.
    val parsedAt = parsed.occurredAt
    val occurredAt = if (parsedAt != null && parsedAt != 0L && !isSameDay(parsedAt, sms.receivedAt)) parsedAt else sms.receivedAt
    // Card digits in the SMS win; kinds that never name a card use the account chosen in setup.
    val hint = parsed.accountHint
    val byDigits = if (!hint.isNullOrEmpty()) matchAccount(hint, parsed.currency, ctx.accounts) else null
    val accountId = byDigits ?: fixedAccountId ?: if (!hint.isNullOrEmpty()) null else matchAccount(null, parsed.currency, ctx.accounts)
    val duplicateOfId = ctx.dedupe.find(DedupeCandidate("", parsed.amountMinor, parsed.currency, parsed.direction, occurredAt, accountId))

    val isIncome = parsed.direction == Direction.CREDIT
    val merchant = parsed.merchant ?: if (isIncome) incomeLabel(sms.body) else null
    val categoryId = suggestCategory(merchant, ctx.rules) ?: if (isIncome) incomeCategory(sms.body) else null

    return IngestResult.Draft(
        TransactionDraft(
            amountMinor = parsed.amountMinor,
            currency = parsed.currency,
            direction = parsed.direction,
            merchant = merchant,
            occurredAt = occurredAt,
            accountId = accountId,
            accountHint = hint,
            categoryId = categoryId,
            duplicateOfId = duplicateOfId,
            smsHash = smsHash(sms),
            smsSender = sms.sender,
            smsBody = sms.body,
            parserId = parsed.parserId
        )
    )
}
