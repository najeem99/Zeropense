package com.najeemfasil.expense.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

fun testDb(): Conn = Db.openConnection(Db.IN_MEMORY).also { migrate(it) }

private fun Conn.count(table: String): Int = first("SELECT COUNT(*) FROM $table") { it.int(0) }!!

class DatabaseTest {
    @Test
    fun freshInstallHasTheSeeds() {
        val db = testDb()
        assertEquals(6, userVersion(db))
        assertEquals(18, db.count("categories"))
        assertEquals(
            listOf("ASPORA" to "remittance"),
            db.query("SELECT merchant_key, category_id FROM merchant_rules") { it.text(0) to it.text(1) }
        )
        // ORDER BY kind, sort, name: expenses first, Remittance before Transfers (both sort 11).
        val names = db.query("SELECT name FROM categories ORDER BY kind, sort, name") { it.text(0) }
        assertEquals(
            listOf(
                "Groceries", "Restaurant", "Transport", "Fuel", "Shopping", "Bills & Utilities", "Rent", "Health",
                "Entertainment", "Subscriptions", "Travel", "Remittance", "Transfers", "Other", "Salary", "Refund",
                "Other income", "Money received"
            ),
            names
        )
        assertEquals(
            setOf("accounts", "categories", "transactions", "merchant_rules", "custom_parsers", "unread_sms", "settings", "sms_rules"),
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'") { it.text(0) }.toSet()
        )
        assertEquals(1, db.first("PRAGMA foreign_keys") { it.int(0) })
    }

    @Test
    fun migratesIdempotently() {
        val db = testDb()
        migrate(db)
        migrate(db)
        assertEquals(6, userVersion(db))
        assertEquals(18, db.count("categories"))
        assertEquals(1, db.count("merchant_rules"))
    }

    @Test
    fun upgradesAnOlderDatabaseStepByStepWithoutLosingData() {
        val db = Db.openConnection(Db.IN_MEMORY)
        migrate(db, upTo = 1)
        assertEquals(16, db.count("categories"))
        db.run("INSERT INTO accounts (id, name, currency, sms_hint, created_at) VALUES ('a', 'ENBD', 'AED', '1234', 1)")
        db.run("INSERT INTO merchant_rules (merchant_key, category_id, updated_at) VALUES ('NOON', 'refund', 1)")
        db.run("INSERT INTO merchant_rules (merchant_key, category_id, updated_at) VALUES ('LULU', 'groceries', 1)")
        migrate(db)
        assertEquals(6, userVersion(db))
        assertEquals("bank", db.first("SELECT kind FROM accounts WHERE id = 'a'") { it.text(0) })
        // V5 drops learned refund rules, V6 adds Aspora.
        assertEquals(
            listOf("ASPORA", "LULU"),
            db.query("SELECT merchant_key FROM merchant_rules ORDER BY merchant_key") { it.text(0) }
        )
        assertEquals(18, db.count("categories"))
    }

    @Test
    fun reopensAnExistingDatabaseFileWithItsData() {
        val file = File.createTempFile("expense", ".db").also { it.delete() }
        try {
            Db.openConnection(file.path).use { db ->
                migrate(db)
                db.run("INSERT INTO accounts (id, name, currency, created_at, kind) VALUES ('a', 'ENBD', 'AED', 1, 'credit_card')")
                setSetting(db, SettingKey.LAST_SMS_SCAN_AT, "123")
            }
            Db.open(file.path).use { db ->
                kotlinx.coroutines.runBlocking {
                    assertEquals(1, db.read { it.count("accounts") })
                    assertEquals("123", db.read { getSetting(it, SettingKey.LAST_SMS_SCAN_AT) })
                    assertEquals("wal", db.read { c -> c.first("PRAGMA journal_mode") { it.text(0) } })
                }
            }
        } finally {
            listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        }
    }

    @Test
    fun resetsToAFreshDatabase() {
        val db = testDb()
        db.run("INSERT INTO accounts (id, name, currency, created_at) VALUES ('a', 'ENBD', 'AED', 1)")
        db.run("DELETE FROM categories WHERE id = 'groceries'")
        db.run("INSERT INTO merchant_rules (merchant_key, category_id, updated_at) VALUES ('LULU', 'other', 1)")
        setSetting(db, SettingKey.LAST_SMS_SCAN_RUN_AT, "5")
        resetAllData(db)
        assertEquals(0, db.count("accounts"))
        assertEquals(18, db.count("categories"))
        assertEquals(listOf("ASPORA"), db.query("SELECT merchant_key FROM merchant_rules") { it.text(0) })
        assertNull(getSetting(db, SettingKey.LAST_SMS_SCAN_RUN_AT))
        assertEquals(6, userVersion(db))
        assertEquals(1, db.first("PRAGMA foreign_keys") { it.int(0) })
    }

    @Test
    fun settingsAreUpserted() {
        val db = testDb()
        assertNull(getSetting(db, SettingKey.LAST_SMS_SCAN_AT))
        setSetting(db, SettingKey.LAST_SMS_SCAN_AT, "1")
        setSetting(db, SettingKey.LAST_SMS_SCAN_AT, "2")
        assertEquals("2", getSetting(db, SettingKey.LAST_SMS_SCAN_AT))
        assertEquals(1, db.count("settings"))
    }

    @Test
    fun foreignKeysAndUniqueConstraintsHold() {
        val db = testDb()
        db.run("INSERT INTO accounts (id, name, currency, created_at) VALUES ('a', 'ENBD', 'AED', 1)")
        val insert =
            "INSERT INTO transactions (id, status, source, amount_minor, currency, direction, occurred_at, account_id, category_id, sms_hash, created_at, updated_at) VALUES (?, 'accepted', 'sms', 100, 'AED', 'debit', 1, 'a', 'groceries', ?, 1, 1)"
        assertEquals(1, db.run(insert, "t1", "h1"))
        assertFailsWith<Exception> { db.run(insert, "t2", "h1") }
        assertEquals(0, db.run(insert.replace("INSERT INTO", "INSERT OR IGNORE INTO"), "t2", "h1"))
        db.run("INSERT INTO merchant_rules (merchant_key, category_id, updated_at) VALUES ('LULU', 'groceries', 1)")
        db.run("DELETE FROM accounts WHERE id = 'a'")
        db.run("DELETE FROM categories WHERE id = 'groceries'")
        assertEquals(
            listOf(null, null),
            db.first("SELECT account_id, category_id FROM transactions WHERE id = 't1'") {
                listOf(it.textOrNull(0), it.textOrNull(1))
            }
        )
        assertEquals(1, db.count("merchant_rules"))
    }

    @Test
    fun transactionsRollBackOnFailure() {
        val db = testDb()
        assertFailsWith<IllegalStateException> {
            db.transaction {
                db.run("DELETE FROM categories")
                error("boom")
            }
        }
        assertEquals(18, db.count("categories"))
    }

    @Test
    fun observersSeeEveryWrite() = runTest(UnconfinedTestDispatcher()) {
        val db = Db.open(Db.IN_MEMORY, UnconfinedTestDispatcher(testScheduler))
        val seen = ArrayList<Int>()
        val job = launch { db.observe { it.count("accounts") }.take(2).toList(seen) }
        db.write { it.run("INSERT INTO accounts (id, name, currency, created_at) VALUES ('a', 'ENBD', 'AED', 1)") }
        job.join()
        assertEquals(listOf(0, 1), seen)
        assertEquals(1, db.observe { it.count("accounts") }.first())
        db.close()
    }
}
