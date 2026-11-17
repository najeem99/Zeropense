package com.najeemfasil.expense.feature.insights

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatMoney
import com.najeemfasil.expense.feature.categories.categoryLook
import kotlin.math.abs

/** One plain-language finding, like "Fuel is up 32% on October". */
data class Highlight(val icon: String, val title: String, val detail: String)

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/**
 * The most useful things to say about the selected period, best first. Only facts the data
 * supports are included, so a quiet period gives a short list.
 */
fun highlights(
    txs: List<InsightTx>,
    currency: Currency,
    filter: String?,
    period: Period,
    start: Long,
    now: Long,
    nameOf: (String?) -> String
): List<Highlight> {
    val out = ArrayList<Highlight>()
    val spends = spending(txs, currency, filter)
    val stats = periodStats(txs, currency, filter, period, start, now)
    val money = { m: Long -> formatMoney(m, currency) }
    val prevName = previousLabel(start, period, now)
    val end = shiftPeriod(start, period, 1)

    if (period == Period.MONTH) {
        forecastMonth(spends, currency, txs, start, now)?.let { f ->
            val vs = if (f.lastMonthMinor > 0) " ${prevName.replaceFirstChar { it.uppercase() }} was ${money(f.lastMonthMinor)}." else ""
            val how = if (f.fromHistory) "Spent so far plus what the rest of the month usually costs." else "At your pace so far."
            out.add(Highlight("🔮", "On track for ${money(f.projectedMinor)} this month", "$how$vs"))
        }
    }

    if (period != Period.DAY && stats.totalMinor > 0) {
        if (filter == null) {
            val changes = categoryBreakdown(txs, currency, period, start)
            // Compare full periods only once this one is over; while running, the biggest absolute jump.
            val up = changes.filter { it.previousMinor > 0 && it.minor > it.previousMinor }.maxByOrNull { it.minor - it.previousMinor }
            if (up != null && !stats.running) {
                val pct = percentChange(up.minor, up.previousMinor)!!
                out.add(
                    Highlight(
                        categoryLook(up.categoryId).icon,
                        "${nameOf(up.categoryId)} up $pct% on $prevName",
                        "${money(up.minor)}, was ${money(up.previousMinor)}."
                    )
                )
            }
            val down = changes.filter { it.previousMinor > it.minor }.maxByOrNull { it.previousMinor - it.minor }
            val gone = txsGone(txs, currency, period, start, changes)
            if (down != null && !stats.running) {
                out.add(
                    Highlight(
                        categoryLook(down.categoryId).icon,
                        "${nameOf(down.categoryId)} down ${abs(percentChange(down.minor, down.previousMinor)!!)}% on $prevName",
                        "${money(down.minor)}, was ${money(down.previousMinor)}. Nice."
                    )
                )
            } else if (gone != null && !stats.running) {
                out.add(Highlight(categoryLook(gone.first).icon, "No ${nameOf(gone.first)} this ${period.noun}", "It was ${money(gone.second)} in $prevName."))
            }
            val top = changes.firstOrNull()
            if (top != null && changes.size > 1) {
                val share = (top.minor * 100.0 / stats.totalMinor).toInt()
                out.add(Highlight(categoryLook(top.categoryId).icon, "${nameOf(top.categoryId)} took $share% of your spending", "${money(top.minor)} across ${plural(top.count, "payment")}."))
            }
        }

        if (stats.elapsedDays >= 3) {
            val streak = if (stats.longestNoSpendStreak > 1) " Longest run: ${stats.longestNoSpendStreak} days in a row." else ""
            when {
                stats.noSpendDays == 0 -> out.add(Highlight("📆", "You spent something every day", "${plural(stats.elapsedDays, "day")}, no day off."))
                else -> out.add(Highlight("🌱", "${plural(stats.noSpendDays, "no-spend day")}", "Out of ${stats.elapsedDays} so far.$streak"))
            }
        }

        stats.topDay?.let { d ->
            val avg = stats.averagePerDayMinor
            if (stats.spendDays > 1 && avg > 0) {
                val times = String.format(java.util.Locale.ROOT, "%.1f", d.minor.toDouble() / avg)
                out.add(Highlight("📈", "Busiest day: ${formatDate(d.start)}", "${money(d.minor)}, $times× your daily average."))
            }
        }

        // The place you went back to most often.
        topMerchants(spends, start, end, Int.MAX_VALUE).maxByOrNull { it.count }?.takeIf { it.count >= 2 }?.let { m ->
            out.add(Highlight("📍", "${m.name}: ${plural(m.count, "visit")}", "${money(m.minor)} in total, ${money(m.minor / m.count)} each on average."))
        }
    }

    stats.biggest?.let { b ->
        if (stats.count > 1) {
            val where = b.merchant?.takeIf { it.isNotBlank() }?.let { " at ${it.trim()}" } ?: ""
            out.add(Highlight("💥", "Largest: ${money(b.amountMinor)}$where", "${formatDate(b.occurredAt)} · ${nameOf(b.categoryId)}"))
        }
    }

    // Weekday habit over the last 13 weeks; only worth saying when one day clearly stands out.
    val week = weekdayAverages(spends, now)
    val maxDay = week.indices.maxByOrNull { week[it] }
    if (maxDay != null && week[maxDay] > 0) {
        val others = week.filterIndexed { i, _ -> i != maxDay }.average()
        if (week[maxDay] >= others * 1.5) {
            out.add(Highlight("🗓️", "${WEEKDAYS_PLURAL[maxDay]} cost you the most", "${money(week[maxDay])} on average, against ${money(others.toLong())} on other days (last 13 weeks)."))
        }
    }
    return out
}

/** A category that had spending last period and none in this one: (id, previous amount). */
private fun txsGone(txs: List<InsightTx>, currency: Currency, period: Period, start: Long, now: List<CategoryChange>): Pair<String?, Long>? {
    val prevStart = shiftPeriod(start, period, -1)
    val ids = now.map { it.categoryId }.toSet()
    return spending(txs, currency, null).filter { it.occurredAt >= prevStart && it.occurredAt < start && it.categoryId !in ids }
        .groupBy { it.categoryId }.mapValues { (_, v) -> v.sumOf { it.amountMinor } }.maxByOrNull { it.value }?.toPair()
}
