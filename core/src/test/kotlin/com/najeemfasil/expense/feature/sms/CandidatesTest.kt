package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val ENBD_SAMPLE = "Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR, DUBAI. Avl Cr. Limit is AED 12,000.00"

class CandidatesTest {
    @Test
    fun listsEveryAmountWithItsCurrencyAndFlagsTheBalance() {
        val c = findAmountCandidates(ENBD_SAMPLE)
        assertEquals(
            listOf(Triple(4550L, Currency.AED, false), Triple(1200000L, Currency.AED, true)),
            c.map { Triple(it.minor, it.currency, it.looksLikeBalance) }
        )
        assertEquals("45.50", ENBD_SAMPLE.substring(c[0].start, c[0].end))
        assertTrue("Purchase of AED 45.50" in c[0].context)
        assertEquals("Purchase of AED 45.50 with Credit Car…", c[0].context)
        assertEquals("12,000.00", ENBD_SAMPLE.substring(c[1].start, c[1].end))
    }

    @Test
    fun includesBareDecimalsWhenTheBankOmitsTheCurrency() {
        val c = findAmountCandidates("A/C X1234 debited by 150.0 on date 11Oct26 trf to ZOMATO Refno 612345678901")
        assertEquals(1, c.size)
        assertEquals(15000L, c[0].minor)
        assertNull(c[0].currency)
    }

    @Test
    fun amountBeforeItsCurrency() {
        val body = "Spent 45.50 AED at X"
        val c = findAmountCandidates(body).single()
        assertEquals("45.50", body.substring(c.start, c.end))
        assertEquals(Currency.AED, c.currency)
    }

    @Test
    fun ignoresReferenceNumbersAndDates() {
        assertEquals(emptyList(), findAmountCandidates("Ref 612345678901 on 11-10-26"))
    }

    @Test
    fun findsMaskedDigits() {
        val cases = listOf(
            "Credit Card ending 1234 at X" to "1234",
            "Acct XX123 debited" to "123",
            "From HDFC Bank A/C *5678" to "5678",
            "A/c no. XX4321 debited" to "4321"
        )
        for ((body, digits) in cases) assertTrue(digits in findDigitsCandidates(body).map { it.digits }, body)
        val d = findDigitsCandidates(ENBD_SAMPLE).first()
        assertEquals("1234", ENBD_SAMPLE.substring(d.start, d.end))
    }
}
