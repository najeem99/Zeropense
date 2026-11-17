package com.najeemfasil.expense.data.db

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.feature.debts.DebtInput
import com.najeemfasil.expense.feature.debts.DebtKind
import com.najeemfasil.expense.feature.debts.addPerson
import com.najeemfasil.expense.feature.debts.listPeopleWithBalances
import com.najeemfasil.expense.feature.debts.saveEntry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupTest {
    private val dir: File = Files.createTempDirectory("backup").toFile()

    private fun fileDb(name: String): Conn = Db.openConnection(File(dir, name).path).also { migrate(it) }

    @Test
    fun exportsAndRestoresEverything() {
        val live = fileDb("live.db")
        assertFalse(hasUserData(live))
        live.run("INSERT INTO accounts (id, name, currency, created_at) VALUES ('a', 'ENBD', 'AED', 1)")
        live.run(
            """INSERT INTO transactions (id, status, source, amount_minor, currency, direction, occurred_at, account_id,
               category_id, created_at, updated_at) VALUES ('t', 'accepted', 'manual', 500, 'AED', 'debit', 1, 'a', 'groceries', 1, 1)"""
        )
        val p = addPerson(live, "Ali", "+971501234567", null)
        saveEntry(live, p, null, DebtInput(DebtKind.GAVE, 1_000, Currency.AED, "lunch", 5))
        assertTrue(hasUserData(live))

        val backup = File(dir, "out/backup.db")
        exportDatabase(live, backup)
        assertTrue(backup.length() > 0)

        // A fresh install: empty database, then restore.
        val fresh = fileDb("fresh.db")
        fresh.run("INSERT INTO accounts (id, name, currency, created_at) VALUES ('x', 'Other', 'INR', 1)")
        val result = restoreDatabase(fresh, backup, File(dir, "work"))
        assertEquals(RestoreResult(transactions = 1, people = 1, debts = 1), result)
        assertEquals(listOf("ENBD"), fresh.query("SELECT name FROM accounts") { it.text(0) })
        assertEquals("a", fresh.first("SELECT account_id FROM transactions") { it.text(0) })
        assertEquals(mapOf(Currency.AED to 1_000L), listPeopleWithBalances(fresh).single().balances)
        assertEquals(1, fresh.first("PRAGMA foreign_keys") { it.int(0) })
        assertEquals("ok", fresh.first("PRAGMA foreign_key_check") { it.text(0) } ?: "ok")
    }

    @Test
    fun upgradesAnOlderBackup() {
        val old = Db.openConnection(File(dir, "old.db").path)
        migrate(old, upTo = 6)
        old.run("INSERT INTO accounts (id, name, currency, created_at, kind) VALUES ('a', 'ENBD', 'AED', 1, 'bank')")
        old.close()
        val fresh = fileDb("fresh2.db")
        restoreDatabase(fresh, File(dir, "old.db"), File(dir, "work"))
        assertEquals(1, fresh.first("SELECT COUNT(*) FROM accounts") { it.int(0) })
        assertEquals(LATEST_VERSION, userVersion(fresh))
    }

    @Test
    fun rejectsFilesThatAreNotBackupsAndKeepsData() {
        val live = fileDb("live3.db")
        live.run("INSERT INTO accounts (id, name, currency, created_at) VALUES ('a', 'ENBD', 'AED', 1)")
        val junk = File(dir, "junk.txt").apply { writeText("hello") }
        assertFailsWith<BackupException> { restoreDatabase(live, junk, File(dir, "work")) }
        val other = Db.openConnection(File(dir, "other.db").path)
        other.run("CREATE TABLE notes (x TEXT)")
        other.close()
        assertFailsWith<BackupException> { restoreDatabase(live, File(dir, "other.db"), File(dir, "work")) }
        assertEquals(1, live.first("SELECT COUNT(*) FROM accounts") { it.int(0) })
    }
}
