package com.najeemfasil.expense.feature.debts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.TelephonyManager
import androidx.activity.result.contract.ActivityResultContract

/** A phone number picked from the phone's contacts. */
data class PickedContact(val name: String, val phone: String?, val lookupKey: String?)

/**
 * Opens the system contact picker on phone numbers. The picker grants access to the one row
 * chosen, so the app needs no contacts permission.
 */
class PickPhoneContact : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

fun readPickedContact(context: Context, uri: Uri): PickedContact? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.LOOKUP_KEY),
        null,
        null,
        null
    )?.use { c ->
        if (!c.moveToFirst()) return@use null
        val phone = c.getString(1)
        val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: phone ?: return@use null
        PickedContact(name, phone, c.getString(2))
    }
}.getOrNull()

private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

private fun simCountry(context: Context): String? {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
    return tm.simCountryIso?.takeIf { it.isNotBlank() } ?: tm.networkCountryIso?.takeIf { it.isNotBlank() }
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/**
 * Sends [text] on WhatsApp. With a number that can be put in international form, the person's
 * chat opens with the message typed in; otherwise WhatsApp asks which chat to send it to.
 * Returns false when WhatsApp isn't installed.
 */
fun shareOnWhatsApp(context: Context, text: String, phone: String?): Boolean {
    val number = whatsAppNumber(phone, simCountry(context))
    for (pkg in WHATSAPP_PACKAGES) {
        if (number != null) {
            val chat = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}"))
                .setPackage(pkg)
            if (context.tryStart(chat)) return true
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)
        if (context.tryStart(send)) return true
    }
    return false
}

/** The system share sheet (SMS, email, any app). */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.tryStart(Intent.createChooser(send, null))
}
