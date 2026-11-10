package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.Db
import com.najeemfasil.expense.data.db.SettingKey
import com.najeemfasil.expense.data.db.getSetting
import com.najeemfasil.expense.feature.transactions.listPending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private val now = localTime(2026, 9, 11, 13, 0)

private fun purchase(amount: Int, at: Long) = InboxMessage(
    "EmiratesNBD",
    "Purchase of AED $amount.00 with Credit Card ending 1234 at SHOP$amount, DUBAI. Avl Cr. Limit is AED 9,000.00",
    at
)

private fun configured(): Db {
    val db = Db.open(Db.IN_MEMORY, Dispatchers.IO)
    runBlocking {
        db.write { c ->
            val sample = purchase(1, 0).body
            replaceSmsRules(
                c,
                listOf("EMIRATESNBD"),
                listOf(SmsRule("", "EMIRATESNBD", templateKey(sample), RuleAction.IMPORT, 0, Direction.DEBIT, Currency.AED, null, sample))
            )
        }
    }
    return db
}

class ScannerTest {
    @Test
    fun doesNothingWithoutPermissionOrSetup() = runBlocking {
        val inbox = FakeSmsInbox(listOf(purchase(5, now - 1000)), granted = false)
        val db = configured()
        assertEquals(ImportSummary(), SmsScanner(db, inbox, { now }).scan())
        val bare = Db.open(Db.IN_MEMORY, Dispatchers.IO)
        assertFalse(bare.read { isSmsConfigured(it) })
        inbox.granted = true
        assertEquals(ImportSummary(), SmsScanner(bare, inbox, { now }).scan())
        assertNull(bare.read { getLastScanRunAt(it) })
    }

    @Test
    fun readsOnlyTheLastDayWithoutAMarkerThenPicksUpFromTheMarker() = runBlocking {
        val db = configured()
        assertTrue(db.read { isSmsConfigured(it) })
        val inbox = FakeSmsInbox(listOf(purchase(1, now - SCAN_FALLBACK_MS - 1), purchase(2, now - 60_000)))
        val scanner = SmsScanner(db, inbox, { now })
        assertEquals(ImportSummary(read = 1, added = 1), scanner.scan())
        assertEquals((now - 60_000).toString(), db.read { getSetting(it, SettingKey.LAST_SMS_SCAN_AT) })
        assertEquals(now, db.read { getLastScanRunAt(it) })
        // Re-scanning reads nothing new; a new SMS is picked up once.
        assertEquals(ImportSummary(), scanner.scan())
        inbox.messages = inbox.messages + purchase(3, now + 5)
        assertEquals(ImportSummary(read = 1, added = 1), scanner.scan())
        assertEquals(ImportSummary(), scanner.scan())
        assertEquals(2, db.read { listPending(it) }.size)
    }

    @Test
    fun pagesThroughEverySenderAndSavesTheMarkerPerPage() = runBlocking {
        val db = configured()
        val msgs = (1..1100).map { i -> if (i % 2 == 0) purchase(i, now - 100_000 + i) else InboxMessage("Other", "hi $i", now - 100_000 + i) }
        val scanner = SmsScanner(db, FakeSmsInbox(msgs.shuffled()), { now })
        val s = scanner.scan()
        assertEquals(1100, s.read)
        assertEquals(550, s.added)
        assertEquals((now - 100_000 + 1100).toString(), db.read { getSetting(it, SettingKey.LAST_SMS_SCAN_AT) })
        assertNull(scanner.progress.value)
    }

    @Test
    fun concurrentCallsShareOneRun() = runBlocking {
        val db = configured()
        val gate = CompletableDeferred<Unit>()
        val inner = FakeSmsInbox(listOf(purchase(7, now - 10)))
        val slow = object : SmsInbox by inner {
            override fun readInbox(afterMs: Long, limit: Int, senders: List<String>?): List<InboxMessage> {
                runBlocking { gate.await() }
                return inner.readInbox(afterMs, limit, senders)
            }
        }
        val scanner = SmsScanner(db, slow, { now })
        val a = scanner.start()
        val b = scanner.start()
        assertSame(a, b)
        gate.complete(Unit)
        scanner.waitForScan()
        assertEquals(ImportSummary(read = 1, added = 1), a.await())
        assertEquals(1, db.read { listPending(it) }.size)
    }
}
