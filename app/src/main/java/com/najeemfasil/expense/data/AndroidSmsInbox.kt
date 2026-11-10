package com.najeemfasil.expense.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import com.najeemfasil.expense.feature.sms.InboxMessage
import com.najeemfasil.expense.feature.sms.MONEY_HINT
import com.najeemfasil.expense.feature.sms.SmsInbox
import com.najeemfasil.expense.feature.sms.SmsPermissionException
import com.najeemfasil.expense.feature.sms.setup.InboxSender

/** The phone's SMS inbox, read through the content resolver (as the Expo app's native module did). */
class AndroidSmsInbox(context: Context) : SmsInbox {
    private val context = context.applicationContext
    private val columns = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)

    override fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override fun listSenders(sinceMs: Long): List<InboxSender> {
        if (!hasPermission()) throw SmsPermissionException()
        class Agg {
            var count = 0
            var moneyCount = 0
            var lastDate = 0L
        }
        val bySender = LinkedHashMap<String, Agg>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            columns,
            "${Telephony.Sms.DATE} > ?",
            arrayOf(sinceMs.toString()),
            null
        )?.use { cursor ->
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val sender = cursor.getString(address) ?: continue
                val agg = bySender.getOrPut(sender) { Agg() }
                agg.count++
                if (MONEY_HINT.containsMatchIn(cursor.getString(body).orEmpty())) agg.moneyCount++
                agg.lastDate = maxOf(agg.lastDate, cursor.getLong(date))
            }
        }
        return bySender.map { (sender, a) -> InboxSender(sender, a.count, a.moneyCount, a.lastDate) }
    }

    override fun readInbox(afterMs: Long, limit: Int, senders: List<String>?): List<InboxMessage> {
        if (!hasPermission()) throw SmsPermissionException()
        var selection = "${Telephony.Sms.DATE} > ?"
        val args = mutableListOf(afterMs.toString())
        if (!senders.isNullOrEmpty()) {
            selection += " AND ${Telephony.Sms.ADDRESS} IN (${senders.joinToString(",") { "?" }})"
            args.addAll(senders)
        }
        val out = ArrayList<InboxMessage>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            columns,
            selection,
            args.toTypedArray(),
            "${Telephony.Sms.DATE} ASC"
        )?.use { cursor ->
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (out.size < limit && cursor.moveToNext()) {
                out.add(InboxMessage(cursor.getString(address).orEmpty(), cursor.getString(body).orEmpty(), cursor.getLong(date)))
            }
        }
        return out
    }
}

/** Defaults to an inbox without permission, so previews and tests never touch the phone. */
val LocalSmsInbox = staticCompositionLocalOf<SmsInbox> { com.najeemfasil.expense.feature.sms.FakeSmsInbox(granted = false) }
