package com.najeemfasil.expense.feature.sms

private val RULES: List<Pair<String, Regex>> = listOf(
    "OTP / verification code" to rx("\\b(otp|one[\\s-]time password|verification code|security code)\\b"),
    "Payment request, not a payment" to rx("\\b(has requested|collect request|requested money|payment request)\\b"),
    "Upcoming debit reminder" to rx("\\b(will be debited|is due|due on|due date|auto[\\s-]?debit.{0,30}scheduled)\\b"),
    "Promotional" to rx("\\b(pre[\\s-]?approved|offer|apply now|click here to avail|cashback up to|win)\\b"),
    "Failed or declined" to rx("\\b(declined|failed|unsuccessful|could not be processed)\\b")
)

/** Banks send from alphanumeric IDs or short codes; long phone numbers are people. */
val PERSONAL_SENDER = rx("^\\+?\\d{8,}$", "")

private val SPACE_OR_HYPHEN = rx("[\\s-]", "")

fun isPersonalSender(sender: String): Boolean = PERSONAL_SENDER.containsMatchIn(sender.replace(SPACE_OR_HYPHEN, ""))

/** Returns why an SMS should be skipped, or null if it may be a real transaction. */
fun skipReason(body: String, sender: String = ""): String? {
    if (isPersonalSender(sender)) return "Personal message"
    return RULES.firstOrNull { it.second.containsMatchIn(body) }?.first
}
