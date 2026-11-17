package com.najeemfasil.expense.feature.insights

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.saveTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class InsightsTest {
    private var n = 0

    private fun tx(
        y: Int,
        m: Int,
        d: Int,
        minor: Long,
        cat: String? = "fuel",
        merchant: String? = "ENOC",
        cur: Currency = Currency.AED,
        dir: Direction = Direction.DEBIT
    ) = InsightTx("t${n++}", minor, cur, dir, cat, merchant, localTime(y, m, d, 12))

    @Test
    fun periodsStartOnMondayAndShift() {
        // Tuesday 17 Nov 2026
        val tue = localTime(2026, 10, 17, 15)
        assertEquals(localTime(2026, 10, 16), periodStart(tue, Period.WEEK))
        assertEquals(localTime(2026, 10, 17), periodStart(tue, Period.DAY))
        assertEquals(localTime(2026, 10, 1), periodStart(tue, Period.MONTH))
        assertEquals(localTime(2026, 0, 1), periodStart(tue, Period.YEAR))
        assertEquals(localTime(2026, 10, 9), shiftPeriod(localTime(2026, 10, 16), Period.WEEK, -1))
        assertEquals(localTime(2025, 0, 1), shiftPeriod(localTime(2026, 0, 1), Period.YEAR, -1))
        // Sunday 1 Feb 2026 belongs to the week of Monday 26 Jan.
        assertEquals(localTime(2026, 0, 26), periodStart(localTime(2026, 1, 1, 23), Period.WEEK))
        assertEquals(1, weekdayIndex(tue))
    }

    @Test
    fun labels() {
        assertEquals("Tue, 17 Nov 2026", periodLabel(localTime(2026, 10, 17), Period.DAY))
        assertEquals("16 – 22 Nov 2026", periodLabel(localTime(2026, 10, 16), Period.WEEK))
        assertEquals("26 Oct – 1 Nov 2026", periodLabel(localTime(2026, 9, 26), Period.WEEK))
        assertEquals("28 Dec 2026 – 3 Jan 2027", periodLabel(localTime(2026, 11, 28), Period.WEEK))
        assertEquals("November 2026", periodLabel(localTime(2026, 10, 1), Period.MONTH))
        assertEquals("2026", periodLabel(localTime(2026, 0, 1), Period.YEAR))
        assertEquals("Nov", periodShortLabel(localTime(2026, 10, 1), Period.MONTH))
        assertEquals("16/11", periodShortLabel(localTime(2026, 10, 16), Period.WEEK))
        val now = localTime(2026, 10, 17, 9)
        assertEquals("October", previousLabel(localTime(2026, 10, 1), Period.MONTH, now))
        assertEquals("last week", previousLabel(localTime(2026, 10, 16), Period.WEEK, now))
        assertEquals("yesterday", previousLabel(localTime(2026, 10, 17), Period.DAY, now))
    }

    @Test
    fun categoryOnlyTotalsByDayWeekMonthYear() {
        val txs = listOf(
            tx(2026, 9, 5, 15000),
            tx(2026, 9, 20, 12000),
            tx(2026, 10, 2, 10000),
            tx(2026, 10, 3, 5000, cat = "groceries", merchant = "Lulu"),
            tx(2026, 10, 16, 8000),
            tx(2026, 10, 17, 9000),
            tx(2026, 10, 17, 300000, cat = "fuel", cur = Currency.INR),
            tx(2026, 10, 17, 2000, cat = "refund", dir = Direction.CREDIT)
        )
        val fuel = spending(txs, Currency.AED, "fuel")
        val nov = localTime(2026, 10, 1)
        assertEquals(listOf(Bucket(localTime(2026, 9, 1), 27000), Bucket(nov, 27000)), trend(fuel, Period.MONTH, nov, 2))
        assertEquals(17000, trend(fuel, Period.WEEK, localTime(2026, 10, 16), 1).single().minor)
        assertEquals(9000, trend(fuel, Period.DAY, localTime(2026, 10, 17), 1).single().minor)
        assertEquals(54000, trend(fuel, Period.YEAR, localTime(2026, 0, 1), 1).single().minor)
        assertEquals(27000, monthsOfYear(fuel, localTime(2026, 0, 1))[10].minor)
        assertEquals(listOf(Currency.AED, Currency.INR), spendingCurrencies(txs))
        assertEquals(listOf("fuel", "groceries"), spendingCategories(txs, Currency.AED))
        assertEquals(0, spending(txs, Currency.AED, UNCATEGORISED).size)
        assertEquals(1, spending(txs + tx(2026, 10, 1, 700, cat = null), Currency.AED, UNCATEGORISED).size)
    }

    @Test
    fun statsForARunningMonthCompareWithTheSameDaysBefore() {
        val txs = listOf(
            tx(2026, 9, 2, 10000),
            tx(2026, 9, 25, 50000),
            tx(2026, 10, 1, 4000),
            tx(2026, 10, 2, 6000),
            tx(2026, 10, 5, 20000),
            tx(2026, 10, 5, 1000, cat = "refund", dir = Direction.CREDIT)
        )
        val now = localTime(2026, 10, 6, 10)
        val s = periodStats(txs, Currency.AED, null, Period.MONTH, localTime(2026, 10, 1), now)
        assertEquals(30000, s.totalMinor)
        assertEquals(3, s.count)
        assertEquals(6, s.elapsedDays)
        assertEquals(5000, s.averagePerDayMinor)
        assertEquals(3, s.noSpendDays)
        assertEquals(2, s.longestNoSpendStreak)
        assertEquals(20000, s.biggest?.amountMinor)
        assertEquals(Bucket(localTime(2026, 10, 5), 20000), s.topDay)
        // Only 1–6 October counts, not the big payment on the 25th.
        assertEquals(10000, s.previousMinor)
        assertTrue(s.running)
        assertEquals(1000, s.refundMinor)
        assertEquals(200, percentChange(30000, 10000))
        assertNull(percentChange(5, 0))

        val oct = periodStats(txs, Currency.AED, "fuel", Period.MONTH, localTime(2026, 9, 1), now)
        assertEquals(31, oct.elapsedDays)
        assertEquals(false, oct.running)
        assertEquals(0, oct.refundMinor)
        val future = periodStats(txs, Currency.AED, null, Period.MONTH, localTime(2026, 11, 1), now)
        assertEquals(0, future.elapsedDays)
        assertEquals(0, future.averagePerDayMinor)
    }

    @Test
    fun breakdownMerchantsAndWeekdays() {
        val txs = listOf(
            tx(2026, 9, 3, 10000),
            tx(2026, 9, 4, 8000, cat = "restaurant", merchant = "Gazebo"),
            tx(2026, 10, 3, 15000),
            tx(2026, 10, 4, 3000, cat = "groceries", merchant = " carrefour  "),
            tx(2026, 10, 9, 4000, cat = "groceries", merchant = "Carrefour")
        )
        val b = categoryBreakdown(txs, Currency.AED, Period.MONTH, localTime(2026, 10, 1))
        assertEquals(listOf(CategoryChange("fuel", 15000, 10000, 1), CategoryChange("groceries", 7000, 0, 2)), b)
        val m = topMerchants(spending(txs, Currency.AED, null), localTime(2026, 10, 1), localTime(2026, 11, 1))
        assertEquals(listOf(MerchantSpend("ENOC", 15000, 1), MerchantSpend("Carrefour", 7000, 2)), m)

        // Fridays: 6 and 13 Nov 2026.
        val w = weekdayAverages(listOf(tx(2026, 10, 6, 7000), tx(2026, 10, 13, 7000)), localTime(2026, 10, 15, 8), days = 14)
        assertEquals(listOf(0L, 0, 0, 0, 7000, 0, 0), w)
    }

    @Test
    fun forecastUsesTheRestOfEarlierMonths() {
        val txs = listOf(
            // October: rent on the 1st, 300 over the rest of the month.
            tx(2026, 9, 1, 400000, cat = "rent", merchant = "Landlord"),
            tx(2026, 9, 12, 10000),
            tx(2026, 9, 20, 20000),
            tx(2026, 10, 1, 400000, cat = "rent", merchant = "Landlord"),
            tx(2026, 10, 3, 5000)
        )
        val nov = localTime(2026, 10, 1)
        val spends = spending(txs, Currency.AED, null)
        val f = forecastMonth(spends, Currency.AED, txs, nov, localTime(2026, 10, 5, 9))
        assertNotNull(f)
        assertEquals(405000, f.spentMinor)
        // Rent is not repeated: 4,050 so far + 300 for the rest of October's days.
        assertEquals(435000, f.projectedMinor)
        assertTrue(f.fromHistory)
        assertEquals(430000, f.lastMonthMinor)
        assertNull(forecastMonth(spends, Currency.AED, txs, nov, localTime(2026, 10, 1, 9)))
        assertNull(forecastMonth(spends, Currency.AED, txs, localTime(2026, 9, 1), localTime(2026, 10, 5)))
        // No earlier month: the pace so far.
        val first = listOf(tx(2026, 10, 1, 3000), tx(2026, 10, 2, 3000))
        val p = forecastMonth(first, Currency.AED, first, nov, localTime(2026, 10, 2, 9))!!
        assertEquals(90000, p.projectedMinor)
        assertEquals(false, p.fromHistory)
    }

    @Test
    fun recurringPayments() {
        val txs = listOf(
            tx(2026, 6, 11, 5500, cat = "subscriptions", merchant = "Netflix"),
            tx(2026, 7, 11, 5500, cat = "subscriptions", merchant = "Netflix"),
            tx(2026, 8, 11, 5500, cat = "subscriptions", merchant = "NETFLIX"),
            tx(2026, 9, 11, 5900, cat = "subscriptions", merchant = "Netflix"),
            // Same shop, random amounts: not a subscription.
            tx(2026, 7, 2, 1000, merchant = "Lulu"),
            tx(2026, 8, 9, 9000, merchant = "Lulu"),
            tx(2026, 9, 1, 30000, merchant = "Lulu"),
            // Only twice.
            tx(2026, 8, 5, 20000, merchant = "DEWA"),
            tx(2026, 9, 5, 20000, merchant = "DEWA")
        )
        val r = findRecurring(spending(txs, Currency.AED, null), localTime(2026, 9, 20))
        assertEquals(1, r.size)
        assertEquals("Netflix", r[0].name)
        assertEquals(5500, r[0].typicalMinor)
        assertEquals(4, r[0].months)
        assertEquals(localTime(2026, 10, 11), r[0].nextAt)
    }

    @Test
    fun compactAmounts() {
        assertEquals("85", formatCompact(8500, Currency.AED))
        assertEquals("1.2k", formatCompact(125000, Currency.AED))
        assertEquals("12k", formatCompact(1290000, Currency.AED))
        assertEquals("2k", formatCompact(200000, Currency.AED))
        assertEquals("1.5L", formatCompact(15000000, Currency.INR))
        assertEquals("1.5M", formatCompact(150000000, Currency.AED))
    }

    @Test
    fun highlightsSayWhatChanged() {
        val txs = listOf(
            tx(2026, 8, 3, 30000),
            tx(2026, 8, 10, 20000, cat = "restaurant", merchant = "Gazebo"),
            tx(2026, 9, 3, 40000),
            tx(2026, 9, 9, 10000, cat = "restaurant", merchant = "Gazebo"),
            tx(2026, 9, 16, 5000, cat = "restaurant", merchant = "Gazebo")
        )
        val names = mapOf("fuel" to "Fuel", "restaurant" to "Restaurant")
        val h = highlights(txs, Currency.AED, null, Period.MONTH, localTime(2026, 9, 1), localTime(2026, 10, 17)) { names[it] ?: "Uncategorised" }
        val titles = h.map { it.title }
        assertTrue("Fuel up 33% on September" in titles, titles.toString())
        assertTrue("Restaurant down 25% on September" in titles, titles.toString())
        assertTrue("Fuel took 72% of your spending" in titles, titles.toString())
        assertTrue("28 no-spend days" in titles, titles.toString())
        assertTrue("Gazebo: 2 visits" in titles, titles.toString())
        assertTrue("Largest: AED 400.00 at ENOC" in titles, titles.toString())
    }

    @Test
    fun readsAcceptedTransactionsFromTheDatabase() = runBlocking {
        val db = Db.open(Db.IN_MEMORY, Dispatchers.Unconfined)
        db.write {
            saveTransaction(it, null, TransactionInput(1500, Currency.AED, Direction.DEBIT, "ENOC", null, localTime(2026, 10, 2), null, "fuel"))
        }
        val rows = db.read { listInsightTxs(it) }
        assertEquals(1, rows.size)
        assertEquals("ENOC", rows[0].merchant)
        assertEquals("fuel", rows[0].categoryId)
    }
}
