package com.najeemfasil.expense.feature.sms

/** A tappable piece of the sample SMS in the format builder (FSD §12.11). */
data class Token(val text: String, val start: Int, val end: Int)

/** Inclusive token range picked for merchant or date. */
data class TokenRange(val from: Int, val to: Int)

private val TOKEN_RE = rx("\\d[\\d,]*(?:\\.\\d+)?|[A-Za-z]+|[^\\sA-Za-z\\d]+", "")

/** Splits so "Rs.500.00" gives "Rs", ".", "500.00" and each piece can be tapped. */
fun tokenize(text: String): List<Token> = TOKEN_RE.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()

/**
 * Tap the first and last word of the field: none → that token; the same lone token → clear;
 * before → extend start; after → extend end; inside → reset to that token.
 */
fun tapToken(current: TokenRange?, i: Int): TokenRange? = when {
    current == null -> TokenRange(i, i)
    current.from == i && current.to == i -> null
    i < current.from -> TokenRange(i, current.to)
    i > current.to -> TokenRange(current.from, i)
    else -> TokenRange(i, i)
}
