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

/** A span of the sample SMS the user marked as a field. `end` is exclusive. */
data class FieldMark(val field: FieldName, val start: Int, val end: Int)

private fun capture(field: FieldName): String = when (field) {
    FieldName.AMOUNT -> "(\\d[\\d,]*(?:\\.\\d+)?)"
    FieldName.MERCHANT, FieldName.DATE -> "(.+?)"
    FieldName.ACCOUNT -> "([A-Za-z*]*\\d+)"
}

private val LITERAL_TOKENS = rx("\\d[\\d,]*(?:\\.\\d+)?|\\s+|[^\\d\\s]", "")
private const val REGEX_SPECIALS = ".*+?^\${}()|[]\\"

/**
 * Turns the text around marked fields into a pattern that tolerates the parts that
 * change between messages: any number ("99.00", "1,204.10", "2026") matches any number,
 * any whitespace any whitespace.
 */
private fun generalizeLiteral(text: String): String {
    val out = StringBuilder()
    for (m in LITERAL_TOKENS.findAll(text)) {
        val token = m.value
        val first = token[0]
        when {
            first in '0'..'9' -> out.append("\\d[\\d,]*(?:\\.\\d+)?")
            com.najeemfasil.expense.core.isJsSpace(first) -> out.append("\\s+")
            else -> for (ch in token) {
                if (ch in REGEX_SPECIALS) out.append('\\')
                out.append(ch)
            }
        }
    }
    return out.toString()
}

sealed interface BuildResult {
    data class Ok(val pattern: String, val fields: List<FieldName>) : BuildResult

    data class Error(val error: String) : BuildResult
}

fun buildPattern(sample: String, marks: List<FieldMark>): BuildResult {
    val sorted = marks.sortedBy { it.start }
    if (sorted.none { it.field == FieldName.AMOUNT }) return BuildResult.Error("Mark the amount.")
    val seen = HashSet<FieldName>()
    for ((i, m) in sorted.withIndex()) {
        if (m.start < 0 || m.end > sample.length || m.end <= m.start) return BuildResult.Error("A marked span is empty.")
        if (!seen.add(m.field)) return BuildResult.Error("${m.field.db} is marked twice.")
        if (i > 0 && m.start < sorted[i - 1].end) return BuildResult.Error("Marked spans overlap.")
    }

    val pattern = StringBuilder()
    var cursor = 0
    for (m in sorted) {
        pattern.append(generalizeLiteral(sample.substring(cursor, m.start))).append(capture(m.field))
        cursor = m.end
    }
    pattern.append(generalizeLiteral(sample.substring(cursor)))
    return BuildResult.Ok(pattern.toString(), sorted.map { it.field })
}
