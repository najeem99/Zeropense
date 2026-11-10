package com.najeemfasil.expense.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun parsesAmounts() {
        assertEquals(12345678L, parseAmountToMinor("1,23,456.78"))
        assertEquals(25000L, parseAmountToMinor("250"))
        assertEquals(1050L, parseAmountToMinor("10.5"))
        assertNull(parseAmountToMinor("0"))
        assertNull(parseAmountToMinor("abc"))
    }

    @Test
    fun truncatesExtraDecimalsAndIgnoresSpaces() {
        assertEquals(199L, parseAmountToMinor("1.999"))
        assertEquals(100050L, parseAmountToMinor(" 1 000.5" + Char(0xA0)))
        assertNull(parseAmountToMinor("1."))
        assertNull(parseAmountToMinor("-5"))
        assertNull(parseAmountToMinor(""))
    }

    @Test
    fun formatsPerCurrency() {
        assertEquals("₹1,23,456.78", formatMoney(12345678, Currency.INR))
        assertEquals("−AED 45.50", formatMoney(-4550, Currency.AED))
    }

    @Test
    fun formatsLikeTheJsLocales() {
        assertEquals("₹12,34,567.50", formatMoney(123456750, Currency.INR))
        assertEquals("AED 1,234,567.50", formatMoney(123456750, Currency.AED))
        assertEquals("₹0.05", formatMoney(5, Currency.INR))
        assertEquals("₹99,999.99", formatMoney(9999999, Currency.INR))
        assertEquals("₹1,00,000.00", formatMoney(10000000, Currency.INR))
        assertEquals("+AED 12.00", formatMoney(1200, Currency.AED, signed = true))
        assertEquals("AED 0.00", formatMoney(0, Currency.AED, signed = true))
        assertEquals("−₹1.00", formatMoney(-100, Currency.INR, signed = true))
    }

    @Test
    fun normalisesCurrencies() {
        assertEquals(Currency.INR, normalizeCurrency("Rs."))
        assertEquals(Currency.INR, normalizeCurrency(" inr "))
        assertEquals(Currency.INR, normalizeCurrency("₹"))
        assertEquals(Currency.AED, normalizeCurrency("Dhs"))
        assertEquals(Currency.AED, normalizeCurrency("dh"))
        assertNull(normalizeCurrency("USD"))
    }

    @Test
    fun minorToInputIsPlain() {
        assertEquals("1234.50", minorToInput(123450))
        assertEquals("0.05", minorToInput(5))
    }
}

class DatesTest {
    private fun ymd(ts: Long?) = ts?.let { toDateInput(it) }

    @Test
    fun parsesBankDateFormats() {
        for (raw in listOf(
            "11-10-26",
            "11/10/2026",
            "11-Oct-26",
            "11Oct26",
            "2026-10-11",
            "11 October 2026",
            "11.10.26"
        )) {
            assertEquals("2026-10-11", ymd(parseSmsDate(raw)), raw)
        }
    }

    @Test
    fun rejectsImpossibleDates() {
        assertNull(parseSmsDate("31-02-26"))
        assertNull(parseSmsDate("11-13-26"))
        assertNull(parseSmsDate("11-Foo-26"))
        assertNull(parseSmsDate("hello"))
    }

    @Test
    fun keepsTheTimeWhenThereIsOne() {
        assertEquals(localTime(2026, 9, 15, 19, 12), parseSmsDate("15/10/2026, 19:12"))
        assertEquals(localTime(2026, 9, 11, 14, 5, 30), parseSmsDate("2026-10-11 14:05:30"))
        assertEquals(localTime(2026, 9, 11, 14, 5), parseSmsDate(" 2026-10-11T14:05 "))
        assertEquals(localTime(2026, 8, 26, 11, 39), parseSmsDate("26/09/2026 11:39"))
    }

    @Test
    fun monthAndDayArithmetic() {
        val t = localTime(2026, 0, 31, 15, 30)
        assertEquals(localTime(2026, 0, 1), startOfMonth(t))
        assertEquals(localTime(2026, 1, 1), addMonths(t, 1))
        assertEquals(localTime(2025, 11, 1), addMonths(t, -1))
        assertEquals(localTime(2026, 0, 31), startOfDay(t))
        assertEquals(localTime(2026, 1, 2), dayAt(t, 2))
        assertEquals(localTime(2026, 0, 2), dayAt(t, -29))
    }

    @Test
    fun displayFormats() {
        val t = localTime(2026, 10, 10, 4, 5)
        assertEquals("November 2026", formatMonth(t))
        assertEquals("10 Nov 2026", formatDate(t))
        assertEquals("10 Nov, 04:05", formatDateTime(t))
        assertEquals("10 Nov", formatDayLabel(t))
        assertEquals("2026-11-10", toDateInput(t))
    }
}

class IdsTest {
    /** Values produced by the Expo app's `hashString`. */
    @Test
    fun hashMatchesTheJsImplementation() {
        assertEquals("811c9dc5", hashString(""))
        assertEquals("e40c292c", hashString("a"))
        assertEquals("4f9f2cab", hashString("hello"))
        assertEquals(
            "a6e48ef6",
            hashString(
                "EmiratesNBD|1791709200000|Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR, DUBAI."
            )
        )
        assertEquals("21fccec0", hashString("₹ 1,23,456.78 paid — café 😀\nline2"))
        assertEquals("9a0999a7", hashString("VM-HDFCBK|0|x"))
    }

    @Test
    fun idsAreUnique() {
        val ids = List(1000) { newId() }
        assertEquals(1000, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[0-9a-z]{16,}")) })
        assertNotEquals(newId(), newId())
    }
}

class JsRegexTest {
    @Test
    fun whitespaceAndDigitsBehaveLikeJavaScript() {
        val nbsp = Char(0xA0)
        val thin = Char(0x2009)
        val bom = Char(0xFEFF)
        assertTrue(jsRegex("^a\\s+b$").matches("a$nbsp${thin}b"))
        assertTrue(jsRegex("^[\\s.]+$").matches(" .$nbsp"))
        assertTrue(jsRegex("^\\d+$").matches("123"))
        assertTrue(!jsRegex("^\\d+$").matches("" + Char(0x661) + Char(0x662)))
        assertTrue(jsRegex("^[^\\d]+$").matches("abc"))
        assertEquals("a b", " a b$nbsp$bom".jsTrim())
    }

    @Test
    fun dollarOnlyMatchesAtTheVeryEnd() {
        assertTrue(!jsRegex("b$").containsMatchIn("ab\n"))
        assertTrue(jsRegex("b$", multiline = true).containsMatchIn("ab\nc"))
        assertTrue(jsRegex("^[$]$").matches("$"))
        assertTrue(jsRegex("^\\$$").matches("$"))
    }

    @Test
    fun bracketInsideAClassIsLiteral() {
        assertTrue(jsRegex("^[.*+?^\${}()|[\\]\\\\]+$").matches("[]()\\"))
    }
}
