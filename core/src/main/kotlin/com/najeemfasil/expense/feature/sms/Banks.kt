package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency

/**
 * A built-in bank. Bank SMS formats change often and differ by product (UPI, card, NEFT), so
 * a profile only adds bank-specific merchant/amount patterns on top of the generic extractor.
 * Formats it misses are covered by the user's own SMS configuration.
 */
class BankProfile(
    val id: String,
    val name: String,
    /** Matched against the SMS sender, e.g. "VM-HDFCBK". */
    val sender: Regex,
    override val defaultCurrency: Currency? = null,
    override val amount: List<Regex> = emptyList(),
    override val merchant: List<Regex> = emptyList()
) : ExtractHints

val BANK_PROFILES: List<BankProfile> = listOf(
    // "Sent Rs.500.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 11/10/26"
    BankProfile("hdfc", "HDFC Bank", rx("HDFC"), Currency.INR, merchant = listOf(rx("^\\s*To\\s+([^\\n]+?)\\s*$", "im"))),
    // "ICICI Bank Acct XX123 debited for Rs 250.00 on 11-Oct-26; SWIGGY credited."
    BankProfile("icici", "ICICI Bank", rx("ICICI"), Currency.INR, merchant = listOf(rx(";\\s*([^;.]+?)\\s+credited"))),
    // "A/C X1234 debited by 150.0 on date 11Oct26 trf to ZOMATO Refno 123"
    BankProfile(
        "sbi",
        "State Bank of India",
        rx("SBI"),
        Currency.INR,
        amount = listOf(rx("(?:debited|credited) by\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)")),
        merchant = listOf(rx("\\btrf to\\s+(.+?)\\s+Ref"), rx("\\btransfer from\\s+(.+?)\\s+Ref"))
    ),
    // "INR 500.00 debited A/c no. XX1234 11-10-26 14:05:21 UPI/P2M/123456/SWIGGY"
    BankProfile("axis", "Axis Bank", rx("AXIS"), Currency.INR, merchant = listOf(rx("UPI\\/P2[AM]\\/\\d+\\/([^\\s/]+)"))),
    // "Sent Rs.120.00 from Kotak Bank AC X1234 to swiggy@ybl on 11-10-26."
    BankProfile("kotak", "Kotak Mahindra Bank", rx("KOTAK|KMBL"), Currency.INR, merchant = listOf(rx("\\bto\\s+([\\w.\\-]+@[\\w]+)")))
)

fun profileForSender(sender: String): BankProfile? = BANK_PROFILES.firstOrNull { it.sender.containsMatchIn(sender) }
