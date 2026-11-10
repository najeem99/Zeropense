package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.parseAmountToMinor
import com.najeemfasil.expense.core.parseSmsDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class FieldName(val db: String) {
    AMOUNT("amount"),
    MERCHANT("merchant"),
    DATE("date"),
    ACCOUNT("account");

    companion object {
        fun from(value: String): FieldName? = entries.firstOrNull { it.db == value }
    }
}

data class CustomParser(
    val id: String,
    val name: String,
    /** Optional case-insensitive substring the sender must contain, e.g. "ENBD". */
    val senderContains: String?,
    /** Regex source (JavaScript dialect) built by [buildPattern]. */
    val pattern: String,
    /** Which field each capture group holds, in order. */
    val fields: List<FieldName>,
    val currency: Currency,
    val direction: Direction
)

/** Compiled patterns; an entry is empty when the stored pattern is not a valid regex. */
private val compiled = ConcurrentHashMap<String, java.util.Optional<Regex>>()

private fun compile(pattern: String): Regex? = compiled.getOrPut(pattern) {
    java.util.Optional.ofNullable(runCatching { rx("^\\s*$pattern\\s*$", "is") }.getOrNull())
}.orElse(null)

private val NON_DIGITS = rx("\\D", "")

fun applyCustomParser(parser: CustomParser, sms: RawSms): ParsedSms? {
    val wanted = parser.senderContains
    if (!wanted.isNullOrEmpty() && !sms.sender.lowercase(Locale.ROOT).contains(wanted.lowercase(Locale.ROOT))) return null
    val match = compile(parser.pattern)?.find(sms.body) ?: return null

    val value = { field: FieldName ->
        val i = parser.fields.indexOf(field)
        if (i == -1 || i + 1 >= match.groups.size) null else match.g(i + 1)
    }
    val amountMinor = parseAmountToMinor(value(FieldName.AMOUNT).orEmpty()) ?: return null
    val merchant = value(FieldName.MERCHANT)
    val date = value(FieldName.DATE)
    val account = value(FieldName.ACCOUNT)

    return ParsedSms(
        amountMinor = amountMinor,
        currency = parser.currency,
        direction = parser.direction,
        merchant = if (merchant.isNullOrEmpty()) null else cleanMerchant(merchant),
        accountHint = if (account.isNullOrEmpty()) null else account.replace(NON_DIGITS, "").takeLast(4).ifEmpty { null },
        occurredAt = if (date.isNullOrEmpty()) null else parseSmsDate(date),
        parserId = "custom:${parser.id}"
    )
}
