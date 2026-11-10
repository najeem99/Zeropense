package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.feature.sms.setup.InboxSender

class SmsPermissionException : IllegalStateException("READ_SMS permission has not been granted")

/** A message as the phone's inbox stores it. */
data class InboxMessage(val sender: String, val body: String, val date: Long)

/**
 * The phone's SMS inbox (FSD §7.6). The Android build reads it through the content resolver;
 * tests use a fake. Every read throws [SmsPermissionException] without READ_SMS.
 */
interface SmsInbox {
    fun hasPermission(): Boolean

    /** One row per sender since [sinceMs], aggregated so bodies don't need to leave the reader. */
    fun listSenders(sinceMs: Long): List<InboxSender>

    /**
     * Messages received strictly after [afterMs], oldest first, at most [limit], optionally only
     * from [senders]. Callers page by passing the last date they got back (FSD §13 Q7).
     */
    fun readInbox(afterMs: Long, limit: Int, senders: List<String>?): List<InboxMessage>
}

const val INBOX_PAGE_SIZE = 500

/** A currency next to a number: enough to tell "this sender sends money messages". */
val MONEY_HINT = Regex("(?i)(INR|Rs\\.?|₹|AED|Dhs?)\\s*[0-9]|[0-9]\\s*(INR|AED)\\b")

/** Reads messages from the given addresses since [sinceMs], oldest first, page by page. */
fun readMessages(inbox: SmsInbox, addresses: List<String>?, sinceMs: Long, onProgress: ((Int) -> Unit)? = null): List<RawSms> {
    if (!inbox.hasPermission()) throw SmsPermissionException()
    val out = ArrayList<RawSms>()
    var after = sinceMs
    while (true) {
        val page = inbox.readInbox(after, INBOX_PAGE_SIZE, addresses)
        for (m in page) out.add(RawSms(m.sender, m.body, m.date))
        onProgress?.invoke(out.size)
        if (page.size < INBOX_PAGE_SIZE) break
        after = page.last().date
    }
    return out
}

/** An in-memory inbox, for tests and previews. */
class FakeSmsInbox(var messages: List<InboxMessage> = emptyList(), var granted: Boolean = true) : SmsInbox {
    override fun hasPermission() = granted

    override fun listSenders(sinceMs: Long): List<InboxSender> {
        if (!granted) throw SmsPermissionException()
        return messages.filter { it.date > sinceMs }.groupBy { it.sender }.map { (sender, list) ->
            InboxSender(sender, list.size, list.count { MONEY_HINT.containsMatchIn(it.body) }, list.maxOf { it.date })
        }
    }

    override fun readInbox(afterMs: Long, limit: Int, senders: List<String>?): List<InboxMessage> {
        if (!granted) throw SmsPermissionException()
        return messages
            .filter { it.date > afterMs && (senders.isNullOrEmpty() || it.sender in senders) }
            .sortedBy { it.date }
            .take(limit)
    }
}

/** After SMS setup, ongoing scans start from here instead of re-reading the inbox. */
fun markScannedUpTo(db: com.najeemfasil.expense.data.db.Conn, ts: Long, now: Long = System.currentTimeMillis()) {
    com.najeemfasil.expense.data.db.setSetting(db, com.najeemfasil.expense.data.db.SettingKey.LAST_SMS_SCAN_AT, ts.toString())
    com.najeemfasil.expense.data.db.setSetting(db, com.najeemfasil.expense.data.db.SettingKey.LAST_SMS_SCAN_RUN_AT, now.toString())
}
