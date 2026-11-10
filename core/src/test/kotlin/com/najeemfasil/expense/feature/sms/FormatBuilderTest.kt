package com.najeemfasil.expense.feature.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatBuilderTest {
    @Test
    fun splitsAmountsWordsAndPunctuation() {
        assertEquals(listOf("Rs", ".", "500.00", "spent", "at", "AMAZON", "-", "IN"), tokenize("Rs.500.00 spent at AMAZON-IN").map { it.text })
        assertEquals(Token("1,200.50", 4, 12), tokenize("AED 1,200.50")[1])
    }

    @Test
    fun tapsSetExtendAndClearRanges() {
        val one = tapToken(null, 3)
        assertEquals(TokenRange(3, 3), one)
        assertNull(tapToken(one, 3))
        assertEquals(TokenRange(1, 3), tapToken(one, 1))
        assertEquals(TokenRange(3, 6), tapToken(one, 6))
        assertEquals(TokenRange(4, 4), tapToken(TokenRange(3, 6), 4))
    }
}
