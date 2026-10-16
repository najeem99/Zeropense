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

class SmsReaderModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private fun hasPermission(): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

  override fun definition() = ModuleDefinition {
    Name("SmsReader")

    Function("hasPermission") { hasPermission() }

    // Inbox messages received strictly after `afterMs`, oldest first, at most `limit`.
    // JS pages through the inbox by passing the last `date` it got back.
    AsyncFunction("readInbox") { afterMs: Double, limit: Int ->
      if (!hasPermission()) throw SmsPermissionException()

      val messages = mutableListOf<Map<String, Any>>()
      context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
        "${Telephony.Sms.DATE} > ?",
        arrayOf(afterMs.toLong().toString()),
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
