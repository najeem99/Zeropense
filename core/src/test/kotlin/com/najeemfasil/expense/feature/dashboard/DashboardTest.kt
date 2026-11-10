package com.najeemfasil.expense.feature.dashboard

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.feature.categories.categoryLook
import com.najeemfasil.expense.feature.transactions.DedupeCandidate
import com.najeemfasil.expense.feature.transactions.DedupeIndex
import com.najeemfasil.expense.feature.transactions.findDuplicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardTest {
    private fun input(minor: Long, currency: Currency, direction: Direction, categoryId: String?) = SummaryInput(minor, currency, direction, categoryId)

    @Test
    fun keepsCurrenciesSeparateAndRanksSpending() {
        val s = summarize(
            listOf(
                input(70000, Currency.INR, Direction.DEBIT, null),
                input(100000, Currency.AED, Direction.CREDIT, null),
                input(3000, Currency.AED, Direction.DEBIT, "food"),
                input(5000, Currency.AED, Direction.DEBIT, "rent"),
                input(2000, Currency.AED, Direction.DEBIT, "food")
            )
        )
        assertEquals(
            listOf(
                CurrencySummary(
                    Currency.AED,
                    100000,
                    10000,
                    0,
                    90000,
                    listOf(CategorySpending("food", 5000), CategorySpending("rent", 5000))
                ),
                CurrencySummary(Currency.INR, 0, 70000, 0, -70000, listOf(CategorySpending(null, 70000)))
            ),
            s
        )
    }

    @Test
    fun refundsLowerSpendingInsteadOfCountingAsIncome() {
        val s = summarize(
            listOf(
                input(1200, Currency.AED, Direction.DEBIT, "shopping"),
                input(1200, Currency.AED, Direction.CREDIT, "refund"),
                input(50000, Currency.AED, Direction.CREDIT, "salary")
            )
        ).single()
        assertEquals(listOf(50000L, 0L, 1200L, 50000L), listOf(s.inMinor, s.outMinor, s.refundMinor, s.netMinor))
    }

    private val nameOf = { id: String? -> id ?: "Uncategorised" }

    @Test
    fun keepsFiveSlicesAndFoldsTheRestIntoAGreyOther() {
        val spending = listOf("groceries", "restaurant", "fuel", "rent", "shopping", "travel", "health")
            .mapIndexed { i, id -> CategorySpending(id, (10 - i) * 100L) }
        val slices = buildSlices(spending, nameOf, dark = false)
        assertEquals(listOf("groceries", "restaurant", "fuel", "rent", "Other (3)"), slices.map { it.label })
        assertEquals(600L + 500 + 400, slices[4].minor)
        assertEquals(categoryLook("groceries", false).color, slices[0].color)
        assertEquals(5, buildSlices(spending.take(5), nameOf, false).count { it.key != "other-slice" })
    }

    @Test
    fun laysTheArcsEndToEndAroundTheRing() {
        val slices = buildSlices(listOf(CategorySpending("rent", 300), CategorySpending("fuel", 100)), nameOf, dark = true)
        val arcs = layoutArcs(slices, 400.0)
        assertEquals(listOf(0.0 to 298.0, 300.0 to 98.0), arcs.map { it.offset to it.dash })
        assertEquals(400.0, layoutArcs(slices.take(1), 400.0).single().dash)
        assertEquals("75%", percentOf(300, 400))
        assertEquals("13%", percentOf(1, 8))
    }

    private fun oct(d: Int, h: Int = 12) = localTime(2026, 9, d, h)

    private fun tx(
        d: Int,
        minor: Long,
        at: Long = oct(d),
        direction: Direction = Direction.DEBIT,
        currency: Currency = Currency.AED
    ) = SummaryInput(minor, currency, direction, null, at)

    @Test
    fun bucketsMoneyOutByDayAndAveragesOverTheDaysSoFar() {
        val r = dailySpending(
            listOf(
                tx(1, 100),
                tx(1, 50, at = oct(1, 23)),
                tx(3, 300),
                tx(2, 999, direction = Direction.CREDIT),
                tx(2, 70, currency = Currency.INR)
            ),
            Currency.AED,
            oct(1, 0),
            31,
            oct(3)
        )
        assertEquals(listOf(150L, 0L, 300L, 0L), r.days.take(4))
        assertEquals(31, r.days.size)
        assertEquals(150, r.averageMinor) // 450 over 3 days
        assertEquals(Peak(2, 300), r.peak)
    }

    @Test
    fun hasNoPeakWithoutSpending() {
        assertNull(dailySpending(emptyList(), Currency.AED, oct(1, 0), 31, oct(31)).peak)
    }

    private val base = DedupeCandidate("", 1000, Currency.AED, Direction.DEBIT, 0, "a")

    @Test
    fun matchesTheClosestWithinTheWindow() {
        val existing = listOf(base.copy(id = "far", occurredAt = 10 * 60_000), base.copy(id = "near", occurredAt = 60_000))
        assertEquals("near", findDuplicate(base, existing))
        assertEquals("near", DedupeIndex(existing).find(base))
        assertEquals("x", findDuplicate(base, listOf(base.copy(id = "x", accountId = null))))
    }

    @Test
    fun ignoresDifferentAmountsAccountsAndOldTransactions() {
        assertNull(findDuplicate(base, listOf(base.copy(id = "x", amountMinor = 1001))))
        assertNull(findDuplicate(base, listOf(base.copy(id = "x", accountId = "b"))))
        assertNull(findDuplicate(base, listOf(base.copy(id = "x", occurredAt = 60 * 60_000))))
        assertNull(DedupeIndex(listOf(base.copy(id = "x", currency = Currency.INR))).find(base))
    }
}
