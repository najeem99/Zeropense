package com.najeemfasil.expense.feature.debts

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.formatDate
import com.najeemfasil.expense.core.formatMoney
import java.util.Locale

/*
 * Texts sent to the other person (e.g. on WhatsApp). They are written from my side towards
 * them: my "You gave" entry reads "I gave you", my "You got" entry reads "You gave me".
 */

private fun entryLine(e: DebtEntry): String {
    val money = formatMoney(e.amountMinor, e.currency)
    val what = if (e.kind == DebtKind.GAVE) "I gave you $money" else "You gave me $money"
    val note = e.note?.let { " · $it" }.orEmpty()
    return "${formatDate(e.occurredAt)}: $what$note"
}

/** "You owe me ₹1,500.00 and AED 20.00" / "I owe you AED 50.00" / "We’re settled up". */
fun balanceSentence(balances: Map<Currency, Long>): String {
    if (balances.isEmpty()) return "We’re settled up."
    val owesMe = balances.filterValues { it > 0 }.map { (c, v) -> formatMoney(v, c) }
    val iOwe = balances.filterValues { it < 0 }.map { (c, v) -> formatMoney(-v, c) }
    val parts = buildList {
        if (owesMe.isNotEmpty()) add("you owe me ${owesMe.joinToString(" and ")}")
        if (iOwe.isNotEmpty()) add("I owe you ${iOwe.joinToString(" and ")}")
    }
    return parts.joinToString("; ").replaceFirstChar { it.titlecase(Locale.ROOT) } + "."
}

/** The whole account with one person, oldest entry first. */
fun personSummaryText(person: DebtPerson, entries: List<DebtEntry>): String = buildString {
    append("Hi ${person.name.substringBefore(' ').ifEmpty { person.name }}, here’s our account:\n\n")
    for (e in entries.sortedBy { it.occurredAt }) append(entryLine(e)).append('\n')
    if (entries.isNotEmpty()) append('\n')
    append("Balance: ").append(balanceSentence(balancesOf(entries)))
}

/** One entry, plus the balance after all entries. */
fun entryShareText(entry: DebtEntry, allEntries: List<DebtEntry>): String = entryLine(entry) + "\nBalance: " + balanceSentence(balancesOf(allEntries))

/**
 * The number in the international form WhatsApp links need ("971501234567"), or null when it
 * can't be known. Numbers saved without a country code get the one of the phone's SIM country
 * (UAE or India); [simCountry] is an ISO code like "ae".
 */
fun whatsAppNumber(phone: String?, simCountry: String?): String? {
    if (phone.isNullOrBlank()) return null
    val trimmed = phone.trim()
    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    if (trimmed.startsWith("+")) return digits.takeIf { it.length >= 8 }
    if (digits.startsWith("00")) return digits.drop(2).takeIf { it.length >= 8 }
    return when (simCountry?.lowercase(Locale.ROOT)) {
        "ae" -> when {
            digits.startsWith("971") && digits.length == 12 -> digits
            digits.startsWith("0") && digits.length == 10 -> "971" + digits.drop(1)
            digits.length == 9 -> "971$digits"
            else -> null
        }
        "in" -> when {
            digits.startsWith("91") && digits.length == 12 -> digits
            digits.startsWith("0") && digits.length == 11 -> "91" + digits.drop(1)
            digits.length == 10 -> "91$digits"
            else -> null
        }
        else -> null
    }
}
