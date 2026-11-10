package com.najeemfasil.expense.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

const val DAY_MS = 24L * 60 * 60 * 1000

private fun calendar(ts: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ts }

/** Local time from parts, like JavaScript's `new Date(y, m, d, hh, mm, ss)` (months from 0, overflow rolls over). */
fun localTime(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long = Calendar.getInstance().apply {
    isLenient = true
    clear()
    set(year, month, day, hour, minute, second)
}.timeInMillis

private fun build(y: Int, m: Int, d: Int, hh: Int, mm: Int, ss: Int): Long? {
    if (m < 0 || m > 11 || d < 1 || d > 31) return null
    val ts = localTime(if (y < 100) 2000 + y else y, m, d, hh, mm, ss)
    // Reject roll-overs like 31-02 becoming 3 March.
    val c = calendar(ts)
    if (c.get(Calendar.DAY_OF_MONTH) != d || c.get(Calendar.MONTH) != m) return null
    return ts
}

private const val TIME = "(?:[ ,]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?"
private val ISO = jsRegex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?")
private val NUMERIC = jsRegex("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})$TIME")
private val NAMED = jsRegex("^(\\d{1,2})[-\\s]?([A-Za-z]{3})[A-Za-z]*[-\\s,]?(\\d{2,4})$TIME")

private fun MatchResult.int(group: Int): Int = groupValues[group].ifEmpty { "0" }.toInt()

/**
 * Parses the date formats Indian and UAE banks use in SMS. Day-first is assumed for numeric
 * dates (dd-mm-yy), which is what those banks send. Returns epoch ms in local time, or null.
 */
fun parseSmsDate(raw: String): Long? {
    val s = raw.jsTrim()
    // 2026-10-11 or 2026-10-11 14:05(:30)
    ISO.find(s)?.let { m -> return build(m.int(1), m.int(2) - 1, m.int(3), m.int(4), m.int(5), m.int(6)) }
    // 11-10-26, 11/10/2026, 11.10.2026 with optional time
    NUMERIC.find(s)?.let { m -> return build(m.int(3), m.int(2) - 1, m.int(1), m.int(4), m.int(5), m.int(6)) }
    // 11-Oct-26, 11 Oct 2026, 11Oct26
    NAMED.find(s)?.let { m ->
        val month = MONTHS.indexOf(m.groupValues[2].lowercase(Locale.ROOT))
        if (month == -1) return null
        return build(m.int(3), month, m.int(1), m.int(4), m.int(5), m.int(6))
    }
    return null
}

fun startOfMonth(ts: Long): Long {
    val c = calendar(ts)
    return localTime(c.get(Calendar.YEAR), c.get(Calendar.MONTH), 1)
}

/** The first day of the month [n] months away. */
fun addMonths(ts: Long, n: Int): Long {
    val c = calendar(ts)
    return localTime(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + n, 1)
}

fun startOfDay(ts: Long): Long {
    val c = calendar(ts)
    return localTime(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
}

/** Midnight of the calendar day [i] days after [from] (safe across daylight-saving changes). */
fun dayAt(from: Long, i: Int): Long {
    val c = calendar(from)
    return localTime(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH) + i)
}

fun isSameDay(a: Long, b: Long): Boolean = startOfDay(a) == startOfDay(b)

// Display formats use British English, like the Expo app's `toLocaleDateString('en-GB', …)`.
private fun format(pattern: String, ts: Long): String = SimpleDateFormat(pattern, Locale.UK).format(Date(ts))

/** "November 2026" */
fun formatMonth(ts: Long): String = format("MMMM yyyy", ts)

/** "10 Nov 2026" */
fun formatDate(ts: Long): String = format("d MMM yyyy", ts)

/** "10 Nov, 14:05" */
fun formatDateTime(ts: Long): String = format("d MMM, HH:mm", ts)

/** "10 Nov", for chart labels. */
fun formatDayLabel(ts: Long): String = format("d MMM", ts)

/** "2026-10-11" for date inputs. */
fun toDateInput(ts: Long): String {
    val c = calendar(ts)
    return "%04d-%02d-%02d".format(
        Locale.ROOT,
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH)
    )
}
