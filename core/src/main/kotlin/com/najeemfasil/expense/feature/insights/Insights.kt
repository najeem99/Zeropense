package com.najeemfasil.expense.feature.insights

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.dayAt
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.startOfDay
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.feature.categories.REFUND_CATEGORY
import com.najeemfasil.expense.feature.categories.countsInTotals
import java.util.Locale
import kotlin.math.floor

/** One accepted transaction, with just what Insights needs. */
data class InsightTx(
    val id: String,
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val categoryId: String?,
    val merchant: String?,
    val occurredAt: Long
) {
    val isSpend: Boolean get() = direction == Direction.DEBIT
    val isRefund: Boolean get() = direction == Direction.CREDIT && categoryId == REFUND_CATEGORY
}

/** Every accepted transaction, oldest first. */
fun listInsightTxs(db: Conn): List<InsightTx> = db.query(
    """SELECT id, amount_minor, currency, direction, category_id, merchant, occurred_at
       FROM transactions WHERE status = 'accepted' ORDER BY occurred_at"""
) {
    InsightTx(
        id = it.text(0),
        amountMinor = it.long(1),
        currency = Currency.from(it.text(2)) ?: Currency.AED,
        direction = Direction.from(it.text(3)) ?: Direction.DEBIT,
        categoryId = it.textOrNull(4),
        merchant = it.textOrNull(5),
        occurredAt = it.long(6)
    )
}

/** Category filter value for transactions without a category. null means every category. */
const val UNCATEGORISED = "__none__"

fun matchesCategory(filter: String?, categoryId: String?): Boolean = filter == null || (filter == UNCATEGORISED && categoryId == null) || filter == categoryId

/**
 * The money-out transactions of one currency and category filter. Every figure in Insights starts
 * here. Credit card bill payments are left out of "every category" (the purchases are already in),
 * but can still be looked at on their own.
 */
fun spending(txs: List<InsightTx>, currency: Currency, filter: String?): List<InsightTx> = txs.filter {
    it.isSpend && it.currency == currency && matchesCategory(filter, it.categoryId) && (filter != null || countsInTotals(it.categoryId))
}

/** Currencies that have any spending, the one with the most transactions first. */
fun spendingCurrencies(txs: List<InsightTx>): List<Currency> = txs.filter { it.isSpend }.groupingBy { it.currency }.eachCount().entries
    .sortedWith(compareByDescending<Map.Entry<Currency, Int>> { it.value }.thenBy { it.key.name }).map { it.key }

/** Category ids (null = uncategorised) with spending in [currency], largest all-time total first; card payments last. */
fun spendingCategories(txs: List<InsightTx>, currency: Currency): List<String?> = txs.filter { it.isSpend && it.currency == currency }
    .groupBy { it.categoryId }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }.entries
    .sortedWith(compareBy<Map.Entry<String?, Long>> { !countsInTotals(it.key) }.thenByDescending { it.value }).map { it.key }

private fun List<InsightTx>.within(from: Long, to: Long) = filter { it.occurredAt >= from && it.occurredAt < to }

/** Spending per calendar day from [from] (a day start), [count] days. */
fun dayTotals(spends: List<InsightTx>, from: Long, count: Int): LongArray {
    val days = LongArray(count)
    for (t in spends) {
        val i = daysBetween(from, startOfDay(t.occurredAt))
        if (i in 0 until count) days[i] += t.amountMinor
    }
    return days
}

data class Bucket(val start: Long, val minor: Long)

/** Totals of the [count] periods that end with the one starting at [anchor], oldest first. */
fun trend(spends: List<InsightTx>, period: Period, anchor: Long, count: Int = period.trendCount): List<Bucket> = (count - 1 downTo 0).map { back ->
    val start = shiftPeriod(anchor, period, -back)
    Bucket(start, spends.within(start, shiftPeriod(start, period, 1)).sumOf { it.amountMinor })
}

/** Totals of the months of the year starting at [yearStart]. */
fun monthsOfYear(spends: List<InsightTx>, yearStart: Long): List<Bucket> = (0 until 12).map { m ->
    val start = shiftPeriod(yearStart, Period.MONTH, m)
    Bucket(start, spends.within(start, shiftPeriod(start, Period.MONTH, 1)).sumOf { it.amountMinor })
}

data class PeriodStats(
    val totalMinor: Long,
    val count: Int,
    /** Days of the period up to today (all of them for a past period, 0 for a future one). */
    val elapsedDays: Int,
    val averagePerDayMinor: Long,
    val spendDays: Int,
    val noSpendDays: Int,
    val longestNoSpendStreak: Int,
    val biggest: InsightTx?,
    /** The busiest day of the period and its total. */
    val topDay: Bucket?,
    /** The previous period up to the same point (all of it when this period is over). */
    val previousMinor: Long,
    /** True while the period is still running, so the comparison is "so far". */
    val running: Boolean,
    /** Refunds received in the period (only counted when every category is shown). */
    val refundMinor: Long
)

/** JavaScript-style rounding, as elsewhere in the app. */
private fun round(x: Double): Long = floor(x + 0.5).toLong()

fun periodStats(
    txs: List<InsightTx>,
    currency: Currency,
    filter: String?,
    period: Period,
    start: Long,
    now: Long
): PeriodStats {
    val spends = spending(txs, currency, filter)
    val end = shiftPeriod(start, period, 1)
    val inPeriod = spends.within(start, end)
    val total = inPeriod.sumOf { it.amountMinor }
    val dayCount = daysBetween(start, end)
    val today = startOfDay(now)
    val elapsed = when {
        today >= end -> dayCount
        today < start -> 0
        else -> daysBetween(start, today) + 1
    }
    val days = dayTotals(inPeriod, start, dayCount)
    var streak = 0
    var longest = 0
    var noSpend = 0
    for (i in 0 until elapsed) {
        if (days[i] == 0L) {
            noSpend++
            streak++
            if (streak > longest) longest = streak
        } else {
            streak = 0
        }
    }
    val topIndex = days.indices.filter { days[it] > 0 }.maxByOrNull { days[it] }
    val running = elapsed in 1 until dayCount
    val prevStart = shiftPeriod(start, period, -1)
    // Running period: compare with the previous one up to the same day, so a half month isn't set against a full one.
    val prevEnd = if (running) minOf(start, dayAt(prevStart, elapsed)) else start
    val refunds = if (filter == null) {
        txs.filter { it.isRefund && it.currency == currency }.within(start, end).sumOf { it.amountMinor }
    } else {
        0L
    }
    return PeriodStats(
        totalMinor = total,
        count = inPeriod.size,
        elapsedDays = elapsed,
        averagePerDayMinor = if (elapsed == 0) 0 else round(total.toDouble() / elapsed),
        spendDays = (0 until elapsed).count { days[it] > 0 },
        noSpendDays = noSpend,
        longestNoSpendStreak = longest,
        biggest = inPeriod.maxByOrNull { it.amountMinor },
        topDay = topIndex?.let { Bucket(dayAt(start, it), days[it]) },
        previousMinor = spends.within(prevStart, prevEnd).sumOf { it.amountMinor },
        running = running,
        refundMinor = refunds
    )
}

/** Percentage change, rounded; null when there is nothing to compare with. */
fun percentChange(now: Long, before: Long): Int? = if (before <= 0) null else round((now - before) * 100.0 / before).toInt()

data class CategoryChange(val categoryId: String?, val minor: Long, val previousMinor: Long, val count: Int)

/** Spending by category in the period, with the full previous period for comparison. Largest first. */
fun categoryBreakdown(txs: List<InsightTx>, currency: Currency, period: Period, start: Long): List<CategoryChange> {
    val spends = spending(txs, currency, null)
    val now = spends.within(start, shiftPeriod(start, period, 1)).groupBy { it.categoryId }
    val before = spends.within(shiftPeriod(start, period, -1), start).groupBy { it.categoryId }
    return now.map { (id, list) -> CategoryChange(id, list.sumOf { it.amountMinor }, before[id]?.sumOf { it.amountMinor } ?: 0, list.size) }
        .sortedByDescending { it.minor }
}

data class MerchantSpend(val name: String, val minor: Long, val count: Int)

/** "Carrefour MCC 123 " and "carrefour mcc 123" are the same place. */
fun merchantKey(name: String): String = name.jsTrim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

/** Where the money went in [from, to), by merchant, largest first. */
fun topMerchants(spends: List<InsightTx>, from: Long, to: Long, limit: Int = 5): List<MerchantSpend> = spends.within(from, to)
    .filter { !it.merchant.isNullOrBlank() }
    .groupBy { merchantKey(it.merchant!!) }
    .map { (_, list) -> MerchantSpend(list.maxBy { it.occurredAt }.merchant!!.jsTrim(), list.sumOf { it.amountMinor }, list.size) }
    .sortedWith(compareByDescending<MerchantSpend> { it.minor }.thenByDescending { it.count })
    .take(limit)

/** Average spending on each weekday (Mon…Sun) over the [days] days ending today. */
fun weekdayAverages(spends: List<InsightTx>, now: Long, days: Int = 91): List<Long> {
    val from = dayAt(startOfDay(now), -(days - 1))
    val totals = dayTotals(spends, from, days)
    val sum = LongArray(7)
    val n = IntArray(7)
    for (i in 0 until days) {
        val w = weekdayIndex(dayAt(from, i))
        sum[w] += totals[i]
        n[w]++
    }
    return (0 until 7).map { if (n[it] == 0) 0L else round(sum[it].toDouble() / n[it]) }
}

data class Forecast(val projectedMinor: Long, val spentMinor: Long, val lastMonthMinor: Long, val fromHistory: Boolean)

/**
 * Where this month is heading: what is spent so far plus what the rest of the month usually
 * costs (the same days of up to three earlier months). A rent paid on the 1st is then not
 * repeated for every remaining day. Without history it falls back to the pace so far.
 * Null unless [monthStart] is the running month and at least two days have passed.
 */
fun forecastMonth(spends: List<InsightTx>, currency: Currency, allTxs: List<InsightTx>, monthStart: Long, now: Long): Forecast? {
    val end = shiftPeriod(monthStart, Period.MONTH, 1)
    val today = startOfDay(now)
    if (today < monthStart || today >= end) return null
    val elapsed = daysBetween(monthStart, today) + 1
    val dayCount = daysBetween(monthStart, end)
    if (elapsed < 2) return null
    val spent = spends.within(monthStart, dayAt(today, 1)).sumOf { it.amountMinor }
    val firstTx = allTxs.filter { it.currency == currency }.minOfOrNull { it.occurredAt }
    val history = (1..3).map { shiftPeriod(monthStart, Period.MONTH, -it) }.filter { m -> firstTx != null && firstTx < shiftPeriod(m, Period.MONTH, 1) }
    val lastMonth = shiftPeriod(monthStart, Period.MONTH, -1)
    val lastMonthTotal = spends.within(lastMonth, monthStart).sumOf { it.amountMinor }
    if (history.isEmpty()) {
        return Forecast(round(spent.toDouble() / elapsed * dayCount), spent, lastMonthTotal, false)
    }
    val rest = history.map { m -> spends.within(dayAt(m, elapsed), shiftPeriod(m, Period.MONTH, 1)).sumOf { it.amountMinor } }
    return Forecast(spent + round(rest.average()), spent, lastMonthTotal, true)
}

data class Recurring(
    val name: String,
    val typicalMinor: Long,
    val categoryId: String?,
    val lastAt: Long,
    val nextAt: Long,
    val months: Int
)

/**
 * Payments that look monthly: the same merchant in at least three different months of the
 * last six, at a similar amount (within 20% of the usual one), about a month apart.
 */
fun findRecurring(spends: List<InsightTx>, now: Long): List<Recurring> {
    val since = dayAt(startOfDay(now), -185)
    return spends.filter { it.occurredAt >= since && it.occurredAt <= now && !it.merchant.isNullOrBlank() }
        .groupBy { merchantKey(it.merchant!!) }
        .mapNotNull { (_, list) ->
            val sorted = list.sortedBy { it.occurredAt }
            val typical = sorted.map { it.amountMinor }.sorted().let { it[it.size / 2] }
            val similar = sorted.filter { kotlin.math.abs(it.amountMinor - typical) * 5 <= typical }
            val months = similar.map { periodStart(it.occurredAt, Period.MONTH) }.distinct()
            if (months.size < 3) return@mapNotNull null
            // One payment per month: keep the last of each month, then look at the gaps.
            val perMonth = similar.groupBy { periodStart(it.occurredAt, Period.MONTH) }.values.map { it.last() }.sortedBy { it.occurredAt }
            val gaps = perMonth.zipWithNext { a, b -> daysBetween(startOfDay(a.occurredAt), startOfDay(b.occurredAt)) }.sorted()
            val gap = gaps[gaps.size / 2]
            if (gap !in 25..36) return@mapNotNull null
            val last = perMonth.last()
            Recurring(last.merchant!!.jsTrim(), typical, last.categoryId, last.occurredAt, dayAt(startOfDay(last.occurredAt), gap), months.size)
        }
        .sortedByDescending { it.typicalMinor }
}

/** Short amounts for calendar cells: "85", "1.2k", "12k", "1.5L" (INR) or "1.5M". */
fun formatCompact(minor: Long, currency: Currency): String {
    val units = minor / 100.0
    fun one(x: Double, suffix: String): String {
        val s = if (x < 10) String.format(Locale.ROOT, "%.1f", floor(x * 10) / 10).removeSuffix(".0") else floor(x).toLong().toString()
        return s + suffix
    }
    return when {
        units < 1000 -> floor(units + 0.5).toLong().toString()
        currency == Currency.INR && units >= 100_000 && units < 10_000_000 -> one(units / 100_000, "L")
        currency == Currency.INR && units >= 10_000_000 -> one(units / 10_000_000, "Cr")
        units < 1_000_000 -> one(units / 1000, "k")
        else -> one(units / 1_000_000, "M")
    }
}
