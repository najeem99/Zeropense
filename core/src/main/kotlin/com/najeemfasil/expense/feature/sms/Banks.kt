package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.jsRegex

/** A built-in bank, recognised by its SMS sender (e.g. "VM-HDFCBK"). */
data class BankProfile(val id: String, val name: String, val sender: Regex)

val BANK_PROFILES: List<BankProfile> = listOf(
    BankProfile("hdfc", "HDFC Bank", jsRegex("HDFC", ignoreCase = true)),
    BankProfile("icici", "ICICI Bank", jsRegex("ICICI", ignoreCase = true)),
    BankProfile("sbi", "State Bank of India", jsRegex("SBI", ignoreCase = true)),
    BankProfile("axis", "Axis Bank", jsRegex("AXIS", ignoreCase = true)),
    BankProfile("kotak", "Kotak Mahindra Bank", jsRegex("KOTAK|KMBL", ignoreCase = true))
)

fun profileForSender(sender: String): BankProfile? = BANK_PROFILES.firstOrNull { it.sender.containsMatchIn(sender) }
