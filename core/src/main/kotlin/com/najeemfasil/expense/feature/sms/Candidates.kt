package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.normalizeCurrency
import com.najeemfasil.expense.core.parseAmountToMinor

/** One number in an SMS that could be the transaction amount. `start`/`end` cover the digits only. */
data class AmountCandidate(
    val start: Int,
    val end: Int,
    val minor: Long,
    val currency: Currency?,
    /** Text around the amount, so the user can tell "spent AED 45" from "Avl bal AED 1,200". */
    val context: String,
    /** Preceded by balance/limit wording: shown, but not suggested. */
    val looksLikeBalance: Boolean
)

data class DigitsCandidate(val start: Int, val end: Int, val digits: String)

private val WITH_CURRENCY = rx("(INR|Rs\\.?|₹|AED|Dhs?)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)|(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*(INR|AED)\\b")

/** Bare decimals like SBI's "debited by 150.0". Whole numbers are too often refs or dates. */
private val BARE_DECIMAL = rx("(?<![\\d.,/-])(\\d[\\d,]*\\.\\d{1,2})(?![\\d/-])", "")
private val BALANCE_BEFORE = rx("(bal(ance)?|limit|avl|available|outstanding|due)[\\s.:a-z]{0,12}$")

private fun contextAround(body: String, start: Int, end: Int): String {
    val from = maxOf(0, start - 24)
    val to = minOf(body.length, end + 16)
    val text = body.substring(from, to).replace(SPACES, " ").jsTrim()
    return (if (from > 0) "…" else "") + text + (if (to < body.length) "…" else "")
}

private fun balanceBefore(body: String, index: Int) = BALANCE_BEFORE.containsMatchIn(body.substring(maxOf(0, index - 20), index))

/** Every amount in the message, in order of appearance. */
fun findAmountCandidates(body: String): List<AmountCandidate> {
    val out = ArrayList<AmountCandidate>()
    for (m in WITH_CURRENCY.findAll(body)) {
        val index = m.range.first
        val prefixed = m.g(2)
        val digits = prefixed ?: m.g(3) ?: continue
        val start = if (prefixed != null) index + m.value.length - digits.length else index
        val minor = parseAmountToMinor(digits) ?: continue
        out.add(
            AmountCandidate(
                start = start,
                end = start + digits.length,
                minor = minor,
                currency = normalizeCurrency(m.g(1) ?: m.g(4).orEmpty()),
                context = contextAround(body, index, index + m.value.length),
                looksLikeBalance = balanceBefore(body, index)
            )
        )
    }
    for (m in BARE_DECIMAL.findAll(body)) {
        val start = m.range.first
        val text = m.groupValues[1]
        val minor = parseAmountToMinor(text) ?: continue
        if (out.any { start >= it.start && start < it.end }) continue
        out.add(
            AmountCandidate(
                start = start,
                end = start + text.length,
                minor = minor,
                currency = null,
                context = contextAround(body, start, start + text.length),
                looksLikeBalance = balanceBefore(body, start)
            )
        )
    }
    return out.sortedBy { it.start }
}

private val DIGITS_AFTER = rx("(?:[x*]+|ending\\s*(?:with|in)?\\s*[:\\-]?\\s*|(?:card|a\\/c|acct|account|ac)\\.?\\s*(?:no\\.?\\s*)?)(\\d{3,6})\\b")

/** Numbers that look like a masked card/account ("XX1234", "card ending 1234", "A/C *5678"). */
fun findDigitsCandidates(body: String): List<DigitsCandidate> {
    val out = ArrayList<DigitsCandidate>()
    for (m in DIGITS_AFTER.findAll(body)) {
        val end = m.range.last + 1
        val digits = m.groupValues[1]
        val start = end - digits.length
        if (out.none { it.start == start }) out.add(DigitsCandidate(start, end, digits))
    }
    return out
}
