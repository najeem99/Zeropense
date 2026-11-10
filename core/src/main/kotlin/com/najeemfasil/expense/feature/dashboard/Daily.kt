package com.najeemfasil.expense.feature.dashboard

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.DAY_MS
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.startOfDay
import kotlin.math.floor

data class Peak(val index: Int, val minor: Long)

data class DailySpending(
    /** Money out per day, index 0 = the day of `from`. */
    val days: List<Long>,
    /** Average over the days up to today (not the whole month while it is still running). */
    val averageMinor: Long,
    val peak: Peak?
)

/** JavaScript's Math.round: halves go up. */
internal fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

/** Whole days between two day starts; rounding absorbs a daylight-saving hour. */
private fun dayIndex(start: Long, ts: Long): Long = jsRound((startOfDay(ts) - start).toDouble() / DAY_MS)

/** Spending (money out) per calendar day for one currency. */
fun dailySpending(txs: List<SummaryInput>, currency: Currency, from: Long, count: Int, today: Long): DailySpending {
    val start = startOfDay(from)
    val days = LongArray(count)
    for (t in txs) {
        if (t.currency != currency || t.direction != Direction.DEBIT) continue
        val i = dayIndex(start, t.occurredAt)
        if (i >= 0 && i < count) days[i.toInt()] += t.amountMinor
    }
    val elapsed = minOf(count.toLong(), maxOf(1L, dayIndex(start, today) + 1)).toInt()
    val total = days.take(elapsed).sum()
    var peak: Peak? = null
    for (index in 0 until count) {
        if (days[index] > 0 && (peak == null || days[index] > peak.minor)) peak = Peak(index, days[index])
    }
    return DailySpending(days.toList(), jsRound(total.toDouble() / elapsed), peak)
}
