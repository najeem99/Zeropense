package com.najeemfasil.expense.core

import java.util.Locale

/** The two currencies of the app. They are never added together. */
enum class Currency(val symbol: String) {
    AED("AED"),
    INR("₹");

    companion object {
        fun from(code: String?): Currency? = entries.firstOrNull { it.name == code }
    }
}

enum class Direction(val db: String) {
    /** Money out. */
    DEBIT("debit"),

    /** Money in. */
    CREDIT("credit");

    companion object {
        fun from(value: String?): Direction? = entries.firstOrNull { it.db == value }
    }
}

/** Maps the many ways banks write a currency ("Rs.", "₹", "INR", "AED") to a [Currency]. */
fun normalizeCurrency(token: String): Currency? {
    val t = token.jsTrim().uppercase(Locale.ROOT).removeSuffix(".")
    return when (t) {
        "INR", "RS", "₹" -> Currency.INR
        "AED", "DHS", "DH" -> Currency.AED
        else -> null
    }
}

private val AMOUNT = Regex("^[0-9]+(\\.[0-9]+)?$")

/**
 * Parses an amount as written in SMS ("1,23,456.78", "250", "1,000.5") into minor units
 * (paise / fils). Extra decimals are cut, not rounded. Null when it isn't a positive number.
 */
fun parseAmountToMinor(raw: String): Long? {
    val cleaned = raw.filterNot { it == ',' || isJsSpace(it) }
    if (!AMOUNT.matches(cleaned)) return null
    val whole = cleaned.substringBefore('.').toLongOrNull() ?: return null
    val frac = (cleaned.substringAfter('.', "") + "00").take(2).toLong()
    if (whole > (Long.MAX_VALUE - frac) / 100) return null
    val minor = whole * 100 + frac
    return if (minor > 0) minor else null
}

/** Digits grouped as 1,234,567 (AED) or 12,34,567 (INR, lakh style). */
private fun group(whole: String, currency: Currency): String {
    if (whole.length <= 3) return whole
    val head = whole.dropLast(3)
    val size = if (currency == Currency.INR) 2 else 3
    val parts = ArrayList<String>()
    var end = head.length
    while (end > 0) {
        val start = maxOf(0, end - size)
        parts.add(head.substring(start, end))
        end = start
    }
    return parts.asReversed().joinToString(",") + "," + whole.takeLast(3)
}

/** "₹1,23,456.78", "AED 1,234.50", "−AED 45.50"; with [signed], positive amounts get a "+". */
fun formatMoney(minor: Long, currency: Currency, signed: Boolean = false): String {
    val abs = if (minor < 0) -minor else minor
    val body = group((abs / 100).toString(), currency) + "." + (abs % 100).toString().padStart(2, '0')
    val sign = if (minor < 0) {
        "−"
    } else if (signed && minor > 0) {
        "+"
    } else {
        ""
    }
    return if (currency == Currency.INR) "$sign${currency.symbol}$body" else "$sign${currency.symbol} $body"
}

/** For editing: minor units back to a plain "1234.50" string. */
fun minorToInput(minor: Long): String {
    val abs = if (minor < 0) -minor else minor
    return (if (minor < 0) "-" else "") + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
}
