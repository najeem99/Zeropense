package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction

data class RawSms(val sender: String, val body: String, val receivedAt: Long)

/** What a parser extracts from one SMS. Fields it can't find are null. */
data class ParsedSms(
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val merchant: String?,
    /** Last digits of the account or card, used to pick the account. */
    val accountHint: String?,
    val occurredAt: Long?,
    /** Which parser produced this: "rule:<id>", "custom:<id>", "bank:<id>" or "generic". */
    val parserId: String
)

internal fun rx(pattern: String, flags: String = "i"): Regex = com.najeemfasil.expense.core.jsRegex(
    pattern,
    ignoreCase = 'i' in flags,
    multiline = 'm' in flags,
    dotAll = 's' in flags
)

/** A capture group's text, or null when the group did not take part (JavaScript's `m[i]`). */
internal fun MatchResult.g(i: Int): String? = groups[i]?.value

internal val SPACES = rx("\\s+", "")
