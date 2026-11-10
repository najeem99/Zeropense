package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.jsRegex
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.core.startOfDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAMPLE = "Dear Customer, AED 87.25 spent at LULU HYPERMARKET on 03-10-2026 using card 4455. Avl bal AED 1,204.10"

private fun mark(field: FieldName, text: String): FieldMark {
    val start = SAMPLE.indexOf(text)
    return FieldMark(field, start, start + text.length)
}

private fun makeParser(marks: List<FieldMark>): CustomParser {
    val built = assertIs<BuildResult.Ok>(buildPattern(SAMPLE, marks))
    return CustomParser("p1", "My bank", "mybank", built.pattern, built.fields, Currency.AED, Direction.DEBIT)
}

private val marks = listOf(
    mark(FieldName.AMOUNT, "87.25"),
    mark(FieldName.MERCHANT, "LULU HYPERMARKET"),
    mark(FieldName.DATE, "03-10-2026"),
    mark(FieldName.ACCOUNT, "4455")
)

class CustomParserTest {
    @Test
    fun readsBackTheSampleItWasBuiltFrom() {
        val r = applyCustomParser(makeParser(marks), RawSms("MyBank", SAMPLE, 0))!!
        assertEquals(ParsedSms(8725, Currency.AED, Direction.DEBIT, "LULU HYPERMARKET", "4455", r.occurredAt, "custom:p1"), r)
        assertEquals(localTime(2026, 9, 3), startOfDay(r.occurredAt!!))
    }

    @Test
    fun matchesANewMessageWithDifferentValuesAndDigitLengths() {
        val body = "Dear Customer, AED 1,250.00 spent at NOON on 14-10-2026 using card 4455. Avl bal AED 99.00"
        val r = applyCustomParser(makeParser(marks), RawSms("MyBank", "  $body\n", 0))!!
        assertEquals(125000L to "NOON", r.amountMinor to r.merchant)
    }

    @Test
    fun ignoresOtherSendersAndOtherFormats() {
        val parser = makeParser(marks)
        assertNull(applyCustomParser(parser, RawSms("Other", SAMPLE, 0)))
        assertNull(applyCustomParser(parser, RawSms("MyBank", "Your OTP is 1234", 0)))
        assertNull(applyCustomParser(parser.copy(pattern = "(unclosed"), RawSms("MyBank", SAMPLE, 0)))
    }

    @Test
    fun escapesRegexCharactersInTheLiteralText() {
        val s = "Txn (POS) of $ 10.00 at A+B Store? [x] {y} a|b ^ \\ end"
        val start = s.indexOf("10.00")
        val built = assertIs<BuildResult.Ok>(buildPattern(s, listOf(FieldMark(FieldName.AMOUNT, start, start + 5))))
        assertTrue(jsRegex("^${built.pattern}$", dotAll = true).containsMatchIn(s))
        // The same source the Expo app would have stored.
        assertEquals(
            "Txn\\s+\\(POS\\)\\s+of\\s+\\$\\s+(\\d[\\d,]*(?:\\.\\d+)?)\\s+at\\s+A\\+B\\s+Store\\?\\s+\\[x\\]\\s+\\{y\\}\\s+a\\|b\\s+\\^\\s+\\\\\\s+end",
            built.pattern
        )
    }

    @Test
    fun rejectsMissingAmountAndOverlappingMarks() {
        assertEquals(BuildResult.Error("Mark the amount."), buildPattern(SAMPLE, listOf(mark(FieldName.MERCHANT, "LULU"))))
        val overlap = buildPattern(SAMPLE, listOf(marks[0], FieldMark(FieldName.MERCHANT, marks[0].start + 1, marks[0].end + 3)))
        assertEquals(BuildResult.Error("Marked spans overlap."), overlap)
        assertEquals(BuildResult.Error("A marked span is empty."), buildPattern(SAMPLE, listOf(FieldMark(FieldName.AMOUNT, 5, 5))))
        assertEquals(
            BuildResult.Error("amount is marked twice."),
            buildPattern(SAMPLE, listOf(marks[0], FieldMark(FieldName.AMOUNT, 0, 2)))
        )
    }

    @Test
    fun buildsAParserFromTheChosenAmountAndCardSkippingTheBalance() {
        val amount = findAmountCandidates(ENBD_SAMPLE)[0]
        val card = findDigitsCandidates(ENBD_SAMPLE)[0]
        val built = assertIs<BuildResult.Ok>(
            buildPattern(
                ENBD_SAMPLE,
                listOf(FieldMark(FieldName.AMOUNT, amount.start, amount.end), FieldMark(FieldName.ACCOUNT, card.start, card.end))
            )
        )
        val parser = CustomParser("p", "ENBD", null, built.pattern, built.fields, Currency.AED, Direction.DEBIT)
        val next = "Purchase of AED 7.00 with Credit Card ending 9876 at CARREFOUR, DUBAI. Avl Cr. Limit is AED 999.00"
        val r = applyCustomParser(parser, RawSms("ENBD", next, 0))!!
        assertEquals(700L to "9876", r.amountMinor to r.accountHint)
    }
}
