package com.najeemfasil.expense.feature.insights

import com.najeemfasil.expense.core.DAY_MS
import com.najeemfasil.expense.core.addMonths
import com.najeemfasil.expense.core.dayAt
import com.najeemfasil.expense.core.formatMonth
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.core.startOfDay
import com.najeemfasil.expense.core.startOfMonth
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** How Insights slices time. Weeks start on Monday. */
enum class Period(val label: String, val noun: String, val trendCount: Int) {
    DAY("Day", "day", 14),
    WEEK("Week", "week", 12),
    MONTH("Month", "month", 12),
    YEAR("Year", "year", 6)
}

private fun calendar(ts: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ts }

private fun fmt(pattern: String, ts: Long): String = SimpleDateFormat(pattern, Locale.UK).format(Date(ts))

/** The start of the day / week / month / year that holds [ts]. */
fun periodStart(ts: Long, period: Period): Long = when (period) {
    Period.DAY -> startOfDay(ts)
    Period.WEEK -> {
        val day = startOfDay(ts)
        val back = (calendar(day).get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        dayAt(day, -back)
    }
    Period.MONTH -> startOfMonth(ts)
    Period.YEAR -> localTime(calendar(ts).get(Calendar.YEAR), 0, 1)
}

/** The start of the period [n] periods after the one starting at [start]. */
fun shiftPeriod(start: Long, period: Period, n: Int): Long = when (period) {
    Period.DAY -> dayAt(start, n)
    Period.WEEK -> dayAt(start, 7 * n)
    Period.MONTH -> addMonths(start, n)
    Period.YEAR -> localTime(calendar(start).get(Calendar.YEAR) + n, 0, 1)
}

/** Whole calendar days between two day starts (rounding absorbs a daylight-saving hour). */
fun daysBetween(from: Long, to: Long): Int = ((to - from).toDouble() / DAY_MS).roundToInt()

/** 0 = Monday … 6 = Sunday. */
fun weekdayIndex(ts: Long): Int = (calendar(ts).get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7

val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
val WEEKDAYS_PLURAL = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")

/** "Tue, 17 Nov 2026", "16 – 22 Nov 2026", "November 2026", "2026". */
fun periodLabel(start: Long, period: Period): String = when (period) {
    Period.DAY -> fmt("EEE, d MMM yyyy", start)
    Period.WEEK -> {
        val end = dayAt(start, 6)
        val a = calendar(start)
        val b = calendar(end)
        when {
            a.get(Calendar.YEAR) != b.get(Calendar.YEAR) -> "${fmt("d MMM yyyy", start)} – ${fmt("d MMM yyyy", end)}"
            a.get(Calendar.MONTH) != b.get(Calendar.MONTH) -> "${fmt("d MMM", start)} – ${fmt("d MMM yyyy", end)}"
            else -> "${fmt("d", start)} – ${fmt("d MMM yyyy", end)}"
        }
    }
    Period.MONTH -> formatMonth(start)
    Period.YEAR -> fmt("yyyy", start)
}

/** Short label under a trend bar: "17", "16/11", "Nov", "2026". */
fun periodShortLabel(start: Long, period: Period): String = when (period) {
    Period.DAY -> fmt("d", start)
    Period.WEEK -> fmt("d/M", start)
    Period.MONTH -> fmt("MMM", start)
    Period.YEAR -> fmt("yyyy", start)
}

/** How the previous period is named in comparisons: "yesterday", "last week", "October", "2025". */
fun previousLabel(start: Long, period: Period, now: Long): String {
    val prev = shiftPeriod(start, period, -1)
    val current = periodStart(now, period) == start
    return when (period) {
        Period.DAY -> if (current) "yesterday" else fmt("EEE d MMM", prev)
        Period.WEEK -> if (current) "last week" else "the week before"
        Period.MONTH -> fmt("MMMM", prev)
        Period.YEAR -> fmt("yyyy", prev)
    }
}
