package com.najeemfasil.expense.feature.transactions

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.DAY_MS
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.data.db.testDb
import com.najeemfasil.expense.feature.accounts.insertSmsTx
import com.najeemfasil.expense.feature.categories.MerchantRule
import com.najeemfasil.expense.feature.categories.listRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun manual(
    amount: Long = 1000,
    merchant: String? = "Cash",
    categoryId: String? = null,
    at: Long = 1_000_000,
    direction: Direction = Direction.DEBIT,
    currency: Currency = Currency.AED
) = TransactionInput(amount, currency, direction, merchant, null, at, null, categoryId)

class TransactionRepositoryTest {
    private fun Conn.status(id: String) = getTransaction(this, id)?.status

    @Test
    fun savingCreatesAnAcceptedManualTransactionAndLearnsTheMerchant() {
        val db = testDb()
        val id = saveTransaction(db, null, manual(merchant = "Carrefour", categoryId = "groceries"))
        val tx = getTransaction(db, id)!!
        assertEquals(TransactionStatus.ACCEPTED, tx.status)
        assertEquals(TransactionSource.MANUAL, tx.source)
        assertEquals(listOf(1000L, "Carrefour", "groceries"), listOf(tx.amountMinor, tx.merchant, tx.categoryId))
        assertTrue(MerchantRule("CARREFOUR", "groceries") in listRules(db))
        assertEquals(0, countPending(db))
    }

    @Test
    fun savingAPendingSmsTransactionAcceptsItAndKeepsItsSms() {
        val db = testDb()
        insertSmsTx(db, "t1", "1234", 500)
        assertEquals(1, countPending(db))
        val tx = listPending(db).single()
        saveTransaction(db, tx.id, manual(amount = 4550, merchant = "Lulu", categoryId = "groceries", at = 600))
        val saved = getTransaction(db, "t1")!!
        assertEquals(TransactionStatus.ACCEPTED, saved.status)
        assertEquals(TransactionSource.SMS, saved.source)
        assertEquals(listOf(4550L, 600L, "body", "1234"), listOf(saved.amountMinor, saved.occurredAt, saved.smsBody, saved.accountHint))
        assertEquals(0, countPending(db))
    }

    @Test
    fun removingDeletesManualOnesAndRejectsSmsOnes() {
        val db = testDb()
        val id = saveTransaction(db, null, manual())
        removeTransaction(db, getTransaction(db, id)!!)
        assertNull(getTransaction(db, id))

        insertSmsTx(db, "t1", "1234", 500)
        removeTransaction(db, getTransaction(db, "t1")!!)
        assertEquals(TransactionStatus.REJECTED, db.status("t1"))
    }

    @Test
    fun setStatusWorksInChunksAndLearnsOnAccept() {
        val db = testDb()
        val ids = (1..1201).map { "t$it" }
        db.transaction { ids.forEach { insertSmsTx(db, it, null, 10) } }
        db.run("UPDATE transactions SET merchant = 'Lulu', category_id = 'groceries' WHERE id = 't1200'")
        setStatus(db, emptyList(), TransactionStatus.ACCEPTED)
        assertEquals(1201, countPending(db))
        setStatus(db, ids, TransactionStatus.REJECTED)
        assertEquals(0, countPending(db))
        assertTrue(listRules(db).none { it.merchantKey == "LULU" })
        setStatus(db, ids, TransactionStatus.ACCEPTED)
        assertEquals(1201, listAccepted(db, 0, 100, limit = 5000).size)
        assertTrue(MerchantRule("LULU", "groceries") in listRules(db))
    }

    @Test
    fun approvesAllWithOrWithoutPossibleDuplicates() {
        val db = testDb()
        insertSmsTx(db, "t1", "1234", 10)
        insertSmsTx(db, "t2", "1234", 20)
        insertSmsTx(db, "t3", "1234", 30)
        db.run("UPDATE transactions SET duplicate_of_id = 't1' WHERE id = 't2'")
        db.run("UPDATE transactions SET merchant = 'Lulu', category_id = 'groceries' WHERE id = 't3'")
        assertEquals(2, approveAllPending(db, false))
        assertEquals(listOf("t2"), listPending(db).map { it.id })
        assertTrue(MerchantRule("LULU", "groceries") in listRules(db))
        assertEquals(1, approveAllPending(db, true))
        assertEquals(0, countPending(db))
    }

    @Test
    fun listsAcceptedInRangeNewestFirstWithALimitAndSummaryInputsWithout() {
        val db = testDb()
        saveTransaction(db, null, manual(amount = 100, at = 10))
        saveTransaction(db, null, manual(amount = 200, at = 20, direction = Direction.CREDIT, categoryId = "refund"))
        saveTransaction(db, null, manual(amount = 300, at = 30, currency = Currency.INR))
        saveTransaction(db, null, manual(amount = 400, at = 40))
        insertSmsTx(db, "p", null, 25)
        assertEquals(listOf(300L, 200L, 100L), listAccepted(db, 10, 40).map { it.amountMinor })
        assertEquals(listOf(300L, 200L), listAccepted(db, 10, 40, limit = 2).map { it.amountMinor })
        val inputs = listSummaryInputs(db, 0, Long.MAX_VALUE).sortedBy { it.occurredAt }
        assertEquals(listOf(100L, 200L, 300L, 400L), inputs.map { it.amountMinor })
        assertEquals(listOf(Direction.CREDIT, "refund", Currency.AED), inputs[1].let { listOf(it.direction, it.categoryId, it.currency) })
        assertEquals(Currency.INR, inputs[2].currency)
    }

    @Test
    fun dedupeCandidatesCoverADayEitherSideAndSkipRejected() {
        val db = testDb()
        val t = 10 * DAY_MS
        saveTransaction(db, null, manual(at = t - DAY_MS))
        saveTransaction(db, null, manual(at = t + DAY_MS + 5))
        saveTransaction(db, null, manual(at = t - DAY_MS - 1))
        insertSmsTx(db, "r", null, t, status = "rejected")
        insertSmsTx(db, "p", null, t)
        val found = listForDedupe(db, t, t + 5)
        assertEquals(3, found.size)
        assertTrue(found.any { it.id == "p" })
    }
}
