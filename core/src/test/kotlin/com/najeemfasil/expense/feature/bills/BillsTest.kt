package com.najeemfasil.expense.feature.bills

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.testDb
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.saveTransaction
import com.najeemfasil.expense.feature.transactions.setExcluded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillsTest {
    private val sep = localTime(2026, 8, 1)
    private val oct = localTime(2026, 9, 1)
    private val nov = localTime(2026, 10, 1)
    private val now = localTime(2026, 10, 17, 9)

    private fun input(kind: BillKind = BillKind.BILL, merchant: String? = "Etisalat", amount: Long = 25_000, day: Int = 20) = BillInput("Landline", kind, amount, Currency.AED, day, "bills", merchant, if (kind == BillKind.EMI) 48 else null, if (kind == BillKind.EMI) 10 else 0, sep)

    private fun pay(db: com.najeemfasil.expense.data.db.Conn, merchant: String, amount: Long, at: Long) = saveTransaction(db, null, TransactionInput(amount, Currency.AED, Direction.DEBIT, merchant, null, at, null, null))

    @Test
    fun dueDateUsesTheLastDayOfShortMonths() {
        assertEquals(localTime(2026, 1, 28), dueDate(localTime(2026, 1, 1), 31))
        assertEquals(localTime(2026, 0, 31), dueDate(localTime(2026, 0, 1), 31))
        assertEquals(2, monthsBetween(sep, nov))
        assertEquals(14, monthsBetween(localTime(2025, 8, 1), nov))
    }

    @Test
    fun aPaymentToTheMerchantMarksTheMonthPaidAndMissedMonthsAreOverdue() {
        val db = testDb()
        val id = saveBill(db, null, input())
        val paid = pay(db, "ETISALAT UAE", 24_800, localTime(2026, 8, 4, 10))
        pay(db, "Carrefour", 25_000, localTime(2026, 9, 5, 10))
        val bill = getBill(db, id)!!
        assertEquals("ETISALAT", bill.merchantKey)
        val months = billMonths(listBills(db), listBillMarks(db), listBillTxs(db, sep), now).getValue(id)
        assertEquals(listOf(nov, oct, sep), months.map { it.month })
        assertEquals(listOf(MonthState.DUE, MonthState.OVERDUE, MonthState.PAID), months.map { it.state })
        assertEquals(paid, months[2].transactionId)
        assertTrue(months[2].auto)
        assertEquals(BillProgress(1, 24_800, null, null, null, null), billProgress(bill, months))

        // Ignored payments don't count.
        setExcluded(db, paid, true)
        assertEquals(MonthState.OVERDUE, billMonths(listBills(db), listBillMarks(db), listBillTxs(db, sep), now).getValue(id)[2].state)
    }

    @Test
    fun marksByHandWithOrWithoutATransactionAndUndoes() {
        val db = testDb()
        val id = saveBill(db, null, input(merchant = null))
        val rent = pay(db, "Bank transfer", 25_000, localTime(2026, 9, 2, 10))
        val candidates = paymentCandidates(getBill(db, id)!!, oct, listBillTxs(db, sep), emptySet())
        assertEquals(listOf(rent), candidates.map { it.id })
        markBillPaid(db, id, oct, rent)
        markBillPaid(db, id, nov, null, now)
        var months = billMonths(listBills(db), listBillMarks(db), listBillTxs(db, sep), now).getValue(id)
        assertEquals(listOf(MonthState.PAID, MonthState.PAID, MonthState.OVERDUE), months.map { it.state })
        assertEquals(localTime(2026, 9, 2, 10), months[1].paidAt)
        unmarkBillPaid(db, id, nov)
        months = billMonths(listBills(db), listBillMarks(db), listBillTxs(db, sep), now).getValue(id)
        assertEquals(MonthState.DUE, months[0].state)
    }

    @Test
    fun emiShowsPaidSoFarAndWhatIsLeft() {
        val db = testDb()
        val id = saveBill(db, null, input(kind = BillKind.EMI, merchant = "Car Loan", amount = 150_000))
        pay(db, "CAR LOAN EMI", 150_000, localTime(2026, 8, 5, 10))
        pay(db, "CAR LOAN EMI", 150_000, localTime(2026, 9, 5, 10))
        val bill = getBill(db, id)!!
        val months = billMonths(listBills(db), listBillMarks(db), listBillTxs(db, sep), now).getValue(id)
        val p = billProgress(bill, months)
        assertEquals(12, p.paidCount)
        assertEquals(36, p.remainingCount)
        assertEquals(12 * 150_000L, p.paidMinor)
        assertEquals(36 * 150_000L, p.remainingMinor)
        // 38 instalments from September 2026: the last is October 2029.
        assertEquals(localTime(2029, 9, 1), p.lastMonth)
    }

    @Test
    fun twoBillsWithTheSameMerchantShareOutPaymentsByAmount() {
        val db = testDb()
        val home = saveBill(db, null, input(amount = 30_000))
        val office = saveBill(db, null, input(amount = 10_000).copy(name = "Office line"))
        pay(db, "Etisalat", 10_100, localTime(2026, 10, 3))
        pay(db, "Etisalat", 29_900, localTime(2026, 10, 4))
        val m = billMonths(listBills(db), listBillMarks(db), listBillTxs(db, nov), now)
        assertEquals(29_900, m.getValue(home)[0].paidMinor)
        assertEquals(10_100, m.getValue(office)[0].paidMinor)
    }

    @Test
    fun deletingABillDropsItsMarks() {
        val db = testDb()
        val id = saveBill(db, null, input())
        markBillPaid(db, id, nov, null)
        deleteBill(db, id)
        assertNull(getBill(db, id))
        assertTrue(listBillMarks(db).isEmpty())
    }
}
