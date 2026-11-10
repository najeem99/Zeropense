package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.core.startOfDay
import com.najeemfasil.expense.feature.transactions.transactionTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val at = localTime(2026, 9, 11, 12, 0)

fun sms(sender: String, body: String, receivedAt: Long = at) = RawSms(sender, body, receivedAt)

class ParseTest {
    @Test
    fun parsesAUaeCardPurchaseAndIgnoresTheAvailableLimitAmount() {
        val r = parseSms(
            sms("EmiratesNBD", "Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR MOE, DUBAI. Avl Cr. Limit is AED 12,000.00"),
            emptyList()
        )
        assertEquals(ParsedSms(4550, Currency.AED, Direction.DEBIT, "CARREFOUR MOE", "1234", null, "generic"), r)
    }

    @Test
    fun parsesAnHdfcMultiLineUpiMessage() {
        val r = parseSms(
            sms("VM-HDFCBK", "Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nOn 11/10/26\nRef 123456789\nNot You?"),
            emptyList()
        )!!
        assertEquals(
            ParsedSms(50000, Currency.INR, Direction.DEBIT, "SWIGGY", "5678", r.occurredAt, "bank:hdfc"),
            r
        )
        assertEquals(localTime(2026, 9, 11), startOfDay(r.occurredAt!!))
    }

    @Test
    fun parsesIciciWhereThePayeeAppearsBeforeCredited() {
        val r = parseSms(
            sms("AD-ICICIB", "ICICI Bank Acct XX123 debited for Rs 250.00 on 11-Oct-26; GAZEBO credited. UPI:123456. Call 18002662."),
            emptyList()
        )!!
        assertEquals(listOf(25000L, Direction.DEBIT, "GAZEBO", "bank:icici"), listOf(r.amountMinor, r.direction, r.merchant, r.parserId))
    }

    @Test
    fun parsesSbiAmountsWrittenWithoutACurrency() {
        val r = parseSms(
            sms("JD-SBIUPI", "Dear UPI user A/C X1234 debited by 150.0 on date 11Oct26 trf to ZOMATO Refno 612345678901"),
            emptyList()
        )!!
        assertEquals(listOf(15000L, Currency.INR, Direction.DEBIT, "ZOMATO"), listOf(r.amountMinor, r.currency, r.direction, r.merchant))
    }

    @Test
    fun parsesAxisUpiMerchantFromTheUpiReference() {
        val r = parseSms(
            sms("AX-AXISBK", "INR 1,250.00 debited A/c no. XX4321 11-10-26 14:05:21 UPI/P2M/612345/AMAZON Not you? SMS BLOCK"),
            emptyList()
        )!!
        assertEquals(listOf(125000L, "AMAZON", "4321"), listOf(r.amountMinor, r.merchant, r.accountHint))
        assertEquals(localTime(2026, 9, 11, 14, 5, 21), r.occurredAt)
    }

    @Test
    fun parsesKotakVpa() {
        val r = parseSms(sms("VK-KOTAKB", "Sent Rs.120.00 from Kotak Bank AC X1234 to swiggy@ybl on 11-10-26."), emptyList())!!
        assertEquals(listOf(12000L, "swiggy@ybl", "1234", "bank:kotak"), listOf(r.amountMinor, r.merchant, r.accountHint, r.parserId))
    }

    @Test
    fun treatsSalaryAsACredit() {
        val r = parseSms(
            sms("ADCB", "Your account XXX9876 has been credited with AED 15,000.00 from ACME TRADING on 01/10/2026."),
            emptyList()
        )!!
        assertEquals(listOf(1500000L, Direction.CREDIT, "ACME TRADING"), listOf(r.amountMinor, r.direction, r.merchant))
    }

    @Test
    fun returnsNullWhenThereIsNoAmount() {
        assertNull(parseSms(sms("VM-HDFCBK", "Your card has been dispatched."), emptyList()))
    }

    @Test
    fun skipsNonTransactions() {
        val cases = listOf(
            "123456 is your OTP for txn of Rs 500 at AMAZON. Do not share." to "OTP / verification code",
            "ABC has requested money from you on Google Pay. Rs 200." to "Payment request, not a payment",
            "Rs 999 will be debited from your account on 15-10-26 for NETFLIX." to "Upcoming debit reminder",
            "Txn of AED 30 at UBER declined due to insufficient funds." to "Failed or declined",
            "You are pre-approved for a loan. Apply now" to "Promotional"
        )
        for ((body, reason) in cases) assertEquals(reason, skipReason(body), body)
        assertNull(skipReason("Rs 500 debited from A/C XX12 to SWIGGY"))
        assertEquals("Personal message", skipReason("Rs 500 debited", "+971 50-123 4567"))
        assertNull(skipReason("Rs 500 debited", "8282"))
    }

    @Test
    fun readsLabelledMerchantFields() {
        assertEquals(
            "AMRK AND SAVE",
            findMerchant("Card XX1234 Purchase, Amount: AED 12.00, Merchant: AMRK AND SAVE, Date 12/10/26", Direction.DEBIT)
        )
        assertEquals(
            "LULU HYPERMARKET",
            findMerchant("AED 8.50 spent on card 1234. Location: LULU HYPERMARKET. Avl bal AED 99", Direction.DEBIT)
        )
    }

    @Test
    fun fallsBackToAShortSnippetWithoutNumbersWhenNoMerchantIsFound() {
        assertEquals("Your card was charged today", smsSnippet("Your card 1234 was charged AED 45.00 today"))
        assertEquals("CARREFOUR", smsSnippet("Purchase of AED 45.50 with card at CARREFOUR, DUBAI."))
        val title = transactionTitle(null, "Your card 1234 was charged AED 45.00 today", Direction.DEBIT)
        assertEquals("Your card was charged today" to true, title.text to title.isSnippet)
    }

    @Test
    fun directionAndIncomeWords() {
        assertEquals(Direction.DEBIT, findDirection("Rs 10 debited from your a/c and credited to SWIGGY"))
        assertEquals(Direction.CREDIT, findDirection("Purchase of AED 5 at NOON was reversed"))
        assertNull(findDirection("Payment by Credit Card ending 1234"))
        assertEquals("salary", incomeCategory("WPS Salary deposited"))
        assertEquals("refund", incomeCategory("Refund of AED 5 received"))
        assertEquals("transfer-in", incomeCategory("AED 5 received via Aani"))
        assertNull(incomeCategory("AED 5 credited"))
        assertEquals("Salary", incomeLabel("Salary Deposited"))
        assertEquals("Refund", incomeLabel("purchase reversed"))
        assertNull(incomeLabel("AED 5 credited"))
    }

    /** FSD §13 Q9: the patterns behave as they did in JavaScript. */
    @Test
    fun regexDialect() {
        val nbsp = Char(0xA0)
        val r = parseSms(sms("ENBD", "purchase of aed${nbsp}45.50 at${nbsp}Carrefour on 11/10/26"), emptyList())!!
        assertEquals(listOf(4550L, Currency.AED, "Carrefour"), listOf(r.amountMinor, r.currency, r.merchant))
        // Dotted/dotless i must not break case-insensitive matching under a Turkish locale.
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals(Currency.INR, parseSms(sms("X", "inr 10 debited"), emptyList())!!.currency)
        } finally {
            java.util.Locale.setDefault(saved)
        }
        // Word boundaries are ASCII: an accented letter ends the word "paid".
        assertTrue(findDirection("paid" + Char(0xE9)) == Direction.DEBIT)
        // Arabic-Indic digits are not digits.
        assertNull(parseSms(sms("X", "AED " + Char(0x661) + Char(0x662) + " spent"), emptyList()))
    }
}
