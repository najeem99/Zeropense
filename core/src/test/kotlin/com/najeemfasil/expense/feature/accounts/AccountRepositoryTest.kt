package com.najeemfasil.expense.feature.accounts

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.data.db.testDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An SMS transaction naming card digits, as the import pipeline (F12) stores it. */
fun insertSmsTx(db: Conn, id: String, hint: String?, at: Long, status: String = "pending", sender: String = "EmiratesNBD") {
    db.run(
        """INSERT INTO transactions (id, status, source, amount_minor, currency, direction, occurred_at, account_hint,
             sms_hash, sms_sender, sms_body, created_at, updated_at)
           VALUES (?, ?, 'sms', 100, 'AED', 'debit', ?, ?, ?, ?, 'body', 1, 1)""",
        id,
        status,
        at,
        hint,
        "hash-$id",
        sender
    )
}

class AccountRepositoryTest {
    @Test
    fun createsTrimsAndListsByKindCurrencyName() {
        val db = testDb()
        createAccount(db, "  Savings ", AccountKind.BANK, Currency.INR, null)
        createAccount(db, "Platinum", AccountKind.CREDIT_CARD, Currency.AED, " 99991234 ")
        createAccount(db, "Debit", AccountKind.DEBIT_CARD, Currency.AED, "")
        val all = listAccounts(db)
        // 'bank' < 'credit_card' < 'debit_card'
        assertEquals(listOf("Savings", "Platinum", "Debit"), all.map { it.name })
        assertEquals("1234", all[1].smsHint)
        assertNull(all[2].smsHint)
        assertEquals("Platinum ••1234", accountLabel(all[1]))
        assertEquals("Debit", accountLabel(all[2]))
    }

    @Test
    fun renameIgnoresEmptyNamesAndDeleteUnlinks() {
        val db = testDb()
        createAccount(db, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234")
        val id = listAccounts(db).single().id
        renameAccount(db, id, "   ")
        assertEquals("ENBD", listAccounts(db).single().name)
        renameAccount(db, id, " Platinum ")
        assertEquals("Platinum", listAccounts(db).single().name)

        insertSmsTx(db, "t1", "1234", 10)
        setAccountHint(db, id, "1234")
        assertEquals(id, db.first("SELECT account_id FROM transactions WHERE id = 't1'") { it.textOrNull(0) })
        deleteAccount(db, id)
        assertNull(db.first("SELECT account_id FROM transactions WHERE id = 't1'") { it.textOrNull(0) })
        // Q12: the hint stays, so the card shows up again under "Found in your SMS".
        assertEquals(listOf("1234"), listDetectedCards(db).map { it.digits })
    }

    @Test
    fun detectsUnknownCardsAndLinksPastTransactionsWhenTheCardIsAdded() {
        val db = testDb()
        insertSmsTx(db, "t1", "1234", 10)
        insertSmsTx(db, "t2", "XX1234", 70, sender = "AD-ENBD")
        insertSmsTx(db, "t3", "7777", 130)
        insertSmsTx(db, "t4", "8888", 200, status = "rejected")
        insertSmsTx(db, "t5", null, 300)
        assertEquals(
            listOf(
                DetectedCard("1234", Currency.AED, 2, "AD-ENBD", 70),
                DetectedCard("7777", Currency.AED, 1, "EmiratesNBD", 130)
            ),
            listDetectedCards(db)
        )
        assertEquals(2, createAccount(db, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234"))
        assertEquals(listOf("7777"), listDetectedCards(db).map { it.digits })
    }
}
