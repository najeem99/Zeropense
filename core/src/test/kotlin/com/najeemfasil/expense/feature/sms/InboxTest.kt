package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.feature.sms.setup.InboxSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InboxTest {
    @Test
    fun readsEveryPageOldestFirstWithProgress() {
        val inbox = FakeSmsInbox((1..1203L).map { InboxMessage(if (it % 2 == 0L) "A" else "B", "AED $it spent", it * 10) }.shuffled())
        val progress = ArrayList<Int>()
        val all = readMessages(inbox, null, 0, progress::add)
        assertEquals(1203, all.size)
        assertEquals(all.sortedBy { it.receivedAt }, all)
        assertEquals(listOf(500, 1000, 1203), progress)
        assertEquals(601, readMessages(inbox, listOf("A"), 0).size)
        assertEquals(3, readMessages(inbox, null, 12000).size)
    }

    @Test
    fun countsSendersAndMoneyMessages() {
        val inbox = FakeSmsInbox(
            listOf(InboxMessage("X", "AED 5 spent", 10), InboxMessage("X", "hello", 20), InboxMessage("Y", "Rs.5", 5))
        )
        assertEquals(listOf(InboxSender("X", 2, 1, 20)), inbox.listSenders(5))
    }

    @Test
    fun nothingRunsWithoutPermission() {
        val inbox = FakeSmsInbox(granted = false)
        assertFailsWith<SmsPermissionException> { readMessages(inbox, null, 0) }
        assertFailsWith<SmsPermissionException> { inbox.listSenders(0) }
        assertEquals("READ_SMS permission has not been granted", SmsPermissionException().message)
    }
}
