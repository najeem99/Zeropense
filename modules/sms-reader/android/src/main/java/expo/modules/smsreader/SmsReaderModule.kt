package expo.modules.smsreader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class SmsPermissionException : CodedException("READ_SMS permission has not been granted")

/** A currency next to a number: enough to tell "this sender sends money messages". */
private val MONEY = Regex("""(?i)(INR|Rs\.?|₹|AED|Dhs?)\s*\d|\d\s*(INR|AED)\b""")

class SmsReaderModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private fun hasPermission(): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

  override fun definition() = ModuleDefinition {
    Name("SmsReader")

    Function("hasPermission") { hasPermission() }

    // One row per sender since `sinceMs`, aggregated here so message bodies never cross into JS.
    AsyncFunction("listSenders") { sinceMs: Double ->
      if (!hasPermission()) throw SmsPermissionException()

      data class Agg(var count: Int = 0, var moneyCount: Int = 0, var lastDate: Long = 0)
      val bySender = LinkedHashMap<String, Agg>()
      context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
        "${Telephony.Sms.DATE} > ?",
        arrayOf(sinceMs.toLong().toString()),
        null
      )?.use { cursor ->
        val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        while (cursor.moveToNext()) {
          val sender = cursor.getString(address) ?: continue
          val agg = bySender.getOrPut(sender) { Agg() }
          agg.count++
          if (MONEY.containsMatchIn(cursor.getString(body) ?: "")) agg.moneyCount++
          agg.lastDate = maxOf(agg.lastDate, cursor.getLong(date))
        }
      }
      bySender.map { (sender, a) ->
        mapOf(
          "sender" to sender,
          "count" to a.count,
          "moneyCount" to a.moneyCount,
          "lastDate" to a.lastDate.toDouble()
        )
      }
    }

    // Inbox messages received strictly after `afterMs`, oldest first, at most `limit`,
    // optionally only from `senders`. JS pages through by passing the last `date` it got back.
    AsyncFunction("readInbox") { afterMs: Double, limit: Int, senders: List<String>? ->
      if (!hasPermission()) throw SmsPermissionException()

      var selection = "${Telephony.Sms.DATE} > ?"
      val args = mutableListOf(afterMs.toLong().toString())
      if (!senders.isNullOrEmpty()) {
        selection += " AND ${Telephony.Sms.ADDRESS} IN (${senders.joinToString(",") { "?" }})"
        args.addAll(senders)
      }

      val messages = mutableListOf<Map<String, Any>>()
      context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
        selection,
        args.toTypedArray(),
        "${Telephony.Sms.DATE} ASC"
      )?.use { cursor ->
        val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        while (messages.size < limit && cursor.moveToNext()) {
          messages.add(
            mapOf(
              "sender" to (cursor.getString(address) ?: ""),
              "body" to (cursor.getString(body) ?: ""),
              "date" to cursor.getLong(date).toDouble()
            )
          )
        }
      }
      messages
    }
  }
}
