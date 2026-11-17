package com.najeemfasil.expense.feature.cards

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.DAY_MS
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.data.db.testDb
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.createAccount
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.feature.categories.CARD_PAYMENT_CATEGORY
import com.najeemfasil.expense.feature.dashboard.summarize
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.getTransaction
import com.najeemfasil.expense.feature.transactions.listAccepted
import com.najeemfasil.expense.feature.transactions.listSummaryInputs
import com.najeemfasil.expense.feature.transactions.saveTransaction
import com.najeemfasil.expense.feature.transactions.setExcluded
import com.najeemfasil.expense.feature.transactions.setPaidCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardPaymentsTest {
    private fun tx(
        id: String,
        amount: Long = 50_000,
        direction: Direction = Direction.DEBIT,
        at: Long = 10 * DAY_MS,
        accountId: String? = null,
        hint: String? = null,
        paid: String? = null,
        body: String? = null
    ) = CardPaymentTx(id, amount, Currency.AED, direction, at, accountId, hint, paid, null, body)

    @Test
    fun findsTheCardFromTheLinkTheCardsOwnSmsOrTheDigitsNamed() {
        val db = testDb()
        createAccount(db, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234")
        createAccount(db, "FAB", AccountKind.CREDIT_CARD, Currency.AED, "5678")
        createAccount(db, "Salary", AccountKind.BANK, Currency.AED, "9999")
        val all = listAccounts(db)
        val cards = creditCards(all)
        val enbd = cards.first { it.name == "ENBD" }.id
        val fab = cards.first { it.name == "FAB" }.id
        val bank = all.first { it.name == "Salary" }.id
        assertEquals(fab to CardMatch.LINKED, resolveCard(tx("a", paid = fab, body = "card 1234"), cards))
        assertEquals(enbd to CardMatch.CARD_SMS, resolveCard(tx("b", accountId = enbd, direction = Direction.CREDIT), cards))
        assertEquals(
            enbd to CardMatch.DIGITS,
            resolveCard(tx("c", accountId = bank, hint = "9999", body = "AED 500 debited from a/c XX9999 towards card XX1234"), cards)
        )
        // Digits inside a longer number don't count; two cards named is ambiguous.
        assertEquals(null to CardMatch.NONE, resolveCard(tx("d", body = "Ref 112345"), cards))
        assertEquals(null to CardMatch.NONE, resolveCard(tx("e", body = "cards 1234 and 5678"), cards))
    }

    @Test
    fun theBankDebitAndTheCardsPaymentReceivedSmsCountOnce() {
        val db = testDb()
        createAccount(db, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234")
        val cards = creditCards(listAccounts(db))
        val card = cards[0].id
        val txs = listOf(
            tx("debit", body = "paid to card 1234", at = 10 * DAY_MS),
            tx("credit", direction = Direction.CREDIT, accountId = card, at = 11 * DAY_MS),
            tx("later", direction = Direction.CREDIT, accountId = card, at = 40 * DAY_MS, amount = 20_000)
        )
        val payments = cardPayments(txs, cards)
        assertEquals(listOf("later", "debit"), payments.map { it.txId })
        assertEquals("credit", payments[1].confirmedById)
        assertEquals(listOf(CardTotal(Currency.AED, 70_000, 2, 40 * DAY_MS)), cardTotals(payments, card))
    }

    @Test
    fun recordedPaymentsAreLinkedAndNeverCountAsSpendingWhenIgnoredEither() {
        val db = testDb()
        db.run("INSERT OR IGNORE INTO categories (id, name, kind, sort) VALUES (?, 'Credit card payment', 'expense', 50)", CARD_PAYMENT_CATEGORY)
        createAccount(db, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234")
        val card = listAccounts(db)[0]
        val id = recordCardPayment(db, card, 120_000, 5 * DAY_MS, null, null)
        val saved = getTransaction(db, id)!!
        assertEquals(CARD_PAYMENT_CATEGORY, saved.categoryId)
        assertEquals(card.id, saved.paidCardId)
        assertEquals(listOf(id), listCardPaymentTxs(db).map { it.id })

        setPaidCard(db, id, null)
        assertNull(getTransaction(db, id)!!.paidCardId)

        setExcluded(db, id, true)
        assertTrue(listCardPaymentTxs(db).isEmpty())
    }

    @Test
    fun ignoredTransactionsLeaveTotalsAndTheListButCanComeBack() {
        val db = testDb()
        val input = TransactionInput(10_000, Currency.AED, Direction.CREDIT, "Friend", null, 5 * DAY_MS, null, null)
        val inId = saveTransaction(db, null, input)
        saveTransaction(db, null, input.copy(direction = Direction.DEBIT, merchant = "Shop", excluded = true))
        assertEquals(10_000, summarize(listSummaryInputs(db, 0, 10 * DAY_MS)).single().inMinor)
        assertEquals(0, summarize(listSummaryInputs(db, 0, 10 * DAY_MS)).single().outMinor)
        assertEquals(listOf(inId), listAccepted(db, 0, 10 * DAY_MS).map { it.id })
        assertEquals(2, listAccepted(db, 0, 10 * DAY_MS, includeExcluded = true).size)

        setExcluded(db, inId, true)
        assertTrue(listSummaryInputs(db, 0, 10 * DAY_MS).isEmpty())
        setExcluded(db, inId, false)
        assertEquals(1, listSummaryInputs(db, 0, 10 * DAY_MS).size)
    }
}
