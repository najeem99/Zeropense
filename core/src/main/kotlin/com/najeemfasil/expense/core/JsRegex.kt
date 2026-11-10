package com.najeemfasil.expense.core

/**
 * Builds a [Regex] from a pattern written for JavaScript (the Expo app's parsers, and custom SMS
 * formats stored by it), so it matches the same text on the JVM and on every Android version:
 *
 * - `\s` matches what JavaScript's does, including the no-break space banks put in SMS;
 * - `\d` is ASCII 0-9 only, `\w` is ASCII letters, digits and underscore, and `\b` is a boundary
 *   between those and anything else (Android's regex engine would use Unicode classes);
 * - `$` only matches at the very end, unless [multiline].
 *
 * See FSD §13 Q9.
 */
fun jsRegex(pattern: String, ignoreCase: Boolean = false, multiline: Boolean = false, dotAll: Boolean = false): Regex {
    val options = buildSet {
        if (ignoreCase) add(RegexOption.IGNORE_CASE)
        if (multiline) add(RegexOption.MULTILINE)
        if (dotAll) add(RegexOption.DOT_MATCHES_ALL)
    }
    return Regex(translateJsPattern(pattern, multiline), options)
}

private const val JS_SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

private const val JS_WORD = "A-Za-z0-9_"
private const val JS_BOUNDARY = "(?:(?<=[$JS_WORD])(?![$JS_WORD])|(?<![$JS_WORD])(?=[$JS_WORD]))"
private const val JS_NOT_BOUNDARY = "(?:(?<=[$JS_WORD])(?=[$JS_WORD])|(?<![$JS_WORD])(?![$JS_WORD]))"

internal fun translateJsPattern(pattern: String, multiline: Boolean = false): String {
    val out = StringBuilder(pattern.length + 16)
    var inClass = false
    var i = 0
    while (i < pattern.length) {
        val ch = pattern[i]
        if (ch == '\\' && i + 1 < pattern.length) {
            val next = pattern[i + 1]
            when (next) {
                's' -> out.append(if (inClass) JS_SPACE else "[$JS_SPACE]")
                'S' -> out.append(if (inClass) "\\S" else "[^$JS_SPACE]")
                'd' -> out.append(if (inClass) "0-9" else "[0-9]")
                'D' -> out.append(if (inClass) "\\D" else "[^0-9]")
                'w' -> out.append(if (inClass) JS_WORD else "[$JS_WORD]")
                'W' -> out.append(if (inClass) "\\W" else "[^$JS_WORD]")
                'b' -> out.append(if (inClass) "\\b" else JS_BOUNDARY)
                'B' -> out.append(if (inClass) "\\B" else JS_NOT_BOUNDARY)
                else -> out.append(ch).append(next)
            }
            i += 2
            continue
        }
        when {
            inClass && ch == ']' -> inClass = false
            // Java treats a bare '[' inside a class as a nested class; JavaScript as a literal.
            inClass && ch == '[' -> out.append('\\')
            !inClass && ch == '[' -> {
                inClass = true
                out.append(ch)
                i++
                // A leading '^' and a literal ']' right after it keep their meaning.
                if (i < pattern.length && pattern[i] == '^') {
                    out.append('^')
                    i++
                }
                continue
            }
            !inClass && ch == '$' && !multiline -> {
                out.append("\\z")
                i++
                continue
            }
        }
        out.append(ch)
        i++
    }
    return out.toString()
}

/** JavaScript's `String.prototype.trim` (also removes no-break spaces and the BOM). */
fun String.jsTrim(): String {
    var start = 0
    var end = length
    while (start < end && isJsSpace(this[start])) start++
    while (end > start && isJsSpace(this[end - 1])) end--
    return substring(start, end)
}

fun isJsSpace(c: Char): Boolean = when (c.code) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> c.code in 0x2000..0x200A
}
