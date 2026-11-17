package com.najeemfasil.expense.feature.debts

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.testDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebtsTest {
    private fun input(kind: DebtKind, minor: Long, cur: Currency = Currency.AED, note: String? = null, at: Long = localTime(2026, 10, 1, 12)) = DebtInput(kind, minor, cur, note, at)

    @Test
    fun balancesPerPersonAndCurrency() {
        val db = testDb()
        val ali = addPerson(db, "Ali", "+971 50 123 4567", "lk-ali")
        val ravi = addPerson(db, "Ravi", "98765 43210", null)
        val sam = addPerson(db, "Sam", null, null)
        saveEntry(db, ali, null, input(DebtKind.GAVE, 10_000))
        saveEntry(db, ali, null, input(DebtKind.GOT, 4_000, note = "paid back"))
        saveEntry(db, ali, null, input(DebtKind.GOT, 50_000, Currency.INR))
        saveEntry(db, ravi, null, input(DebtKind.GOT, 2_500))
        val e = saveEntry(db, sam, null, input(DebtKind.GAVE, 100))
        saveEntry(db, sam, null, input(DebtKind.GOT, 100))

        val people = listPeopleWithBalances(db)
        val byName = people.associateBy { it.person.name }
        assertEquals(mapOf(Currency.AED to 6_000L, Currency.INR to -50_000L), byName.getValue("Ali").balances)
        assertEquals(mapOf(Currency.AED to -2_500L), byName.getValue("Ravi").balances)
        assertTrue(byName.getValue("Sam").balances.isEmpty())
        // Settled people last.
        assertEquals("Sam", people.last().person.name)

        val t = totals(people)
        assertEquals(mapOf(Currency.AED to 6_000L), t.toGet)
        assertEquals(mapOf(Currency.AED to 2_500L, Currency.INR to 50_000L), t.toGive)

        assertEquals(DebtKind.GAVE, getEntry(db, e)?.kind)
    }

    @Test
    fun pickingTheSameContactOrNumberTwiceReusesThePerson() {
        val db = testDb()
        val a = addPerson(db, "Ali", "+971501234567", "lk-ali")
        assertEquals(a, addPerson(db, "Ali Hassan", "+971 50 123 4567", null))
        assertEquals(a, addPerson(db, "Ali", "000", "lk-ali"))
        val b = addPerson(db, "Bob", null, null)
        assertTrue(a != b)
        assertEquals(2, listPeopleWithBalances(db).size)
    }

    @Test
    fun editsAndDeletes() {
        val db = testDb()
        val p = addPerson(db, "Ali", null, null)
        val e = saveEntry(db, p, null, input(DebtKind.GAVE, 1_000, note = " lunch "))
        assertEquals("lunch", getEntry(db, e)?.note)
        val newDate = localTime(2026, 0, 5, 12)
        saveEntry(db, p, e, input(DebtKind.GOT, 2_000, Currency.INR, note = "", at = newDate))
        val edited = getEntry(db, e)!!
        assertEquals(DebtKind.GOT, edited.kind)
        assertEquals(2_000, edited.amountMinor)
        assertEquals(Currency.INR, edited.currency)
        assertNull(edited.note)
        assertEquals(newDate, edited.occurredAt)

        updatePerson(db, p, "  Ali H ", " 0501234567 ")
        assertEquals(DebtPerson(p, "Ali H", "0501234567", null), getPerson(db, p))
        updatePerson(db, p, "  ", null)
        assertEquals("Ali H", getPerson(db, p)?.name)

        deleteEntry(db, e)
        assertTrue(listEntries(db, p).isEmpty())
        saveEntry(db, p, null, input(DebtKind.GAVE, 1))
        deletePerson(db, p)
        assertNull(getPerson(db, p))
        assertEquals(0, db.first("SELECT COUNT(*) FROM debts") { it.int(0) })
    }

    @Test
    fun entriesNewestFirst() {
        val db = testDb()
        val p = addPerson(db, "Ali", null, null)
        saveEntry(db, p, null, input(DebtKind.GAVE, 1, at = localTime(2026, 1, 1)))
        saveEntry(db, p, null, input(DebtKind.GAVE, 2, at = localTime(2026, 3, 1)))
        saveEntry(db, p, null, input(DebtKind.GAVE, 3, at = localTime(2026, 2, 1)))
        assertEquals(listOf(2L, 3L, 1L), listEntries(db, p).map { it.amountMinor })
    }

    @Test
    fun shareTexts() {
        val p = DebtPerson("p", "Ali Hassan", null, null)
        val entries = listOf(
            DebtEntry("1", "p", DebtKind.GAVE, 10_000, Currency.AED, "Dinner", localTime(2026, 10, 2, 12)),
            DebtEntry("2", "p", DebtKind.GOT, 4_000, Currency.AED, null, localTime(2026, 10, 5, 12)),
            DebtEntry("3", "p", DebtKind.GOT, 150_000, Currency.INR, null, localTime(2026, 10, 4, 12))
        )
        assertEquals(
            "Hi Ali, here’s our account:\n\n" +
                "2 Nov 2026: I gave you AED 100.00 · Dinner\n" +
                "4 Nov 2026: You gave me ₹1,500.00\n" +
                "5 Nov 2026: You gave me AED 40.00\n\n" +
                "Balance: You owe me AED 60.00; I owe you ₹1,500.00.",
            personSummaryText(p, entries)
        )
        assertEquals(
            "2 Nov 2026: I gave you AED 100.00 · Dinner\nBalance: You owe me AED 60.00; I owe you ₹1,500.00.",
            entryShareText(entries[0], entries)
        )
        assertEquals("We’re settled up.", balanceSentence(emptyMap()))
        assertEquals("I owe you AED 5.00.", balanceSentence(mapOf(Currency.AED to -500L)))
    }

    @Test
    fun whatsAppNumbers() {
        assertEquals("971501234567", whatsAppNumber("+971 50 123 4567", null))
        assertEquals("971501234567", whatsAppNumber("00971501234567", "in"))
        assertEquals("971501234567", whatsAppNumber("050-123 4567", "ae"))
        assertEquals("971501234567", whatsAppNumber("501234567", "AE"))
        assertEquals("919876543210", whatsAppNumber("98765 43210", "in"))
        assertEquals("919876543210", whatsAppNumber("098765 43210", "in"))
        assertEquals("919876543210", whatsAppNumber("91 98765 43210", "in"))
        assertNull(whatsAppNumber("98765 43210", null))
        assertNull(whatsAppNumber("98765 43210", "ae"))
        assertNull(whatsAppNumber("", "ae"))
        assertNull(whatsAppNumber(null, "ae"))
    }
}
