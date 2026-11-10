package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.sms.setup.CardChoice
import com.najeemfasil.expense.feature.sms.setup.InboxSender
import com.najeemfasil.expense.feature.sms.setup.buildRules
import com.najeemfasil.expense.feature.sms.setup.cardChoiceComplete
import com.najeemfasil.expense.feature.sms.setup.collectDigits
import com.najeemfasil.expense.feature.sms.setup.countIncluded
import com.najeemfasil.expense.feature.sms.setup.initialChoices
import com.najeemfasil.expense.feature.sms.setup.kindId
import com.najeemfasil.expense.feature.sms.setup.kindsWithoutDigits
import com.najeemfasil.expense.feature.sms.setup.mergeSenders
import com.najeemfasil.expense.feature.sms.setup.periodStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupStateTest {
    @Test
    fun dropsPersonalNumbersAndPreTicksBankLikeSenders() {
        val senders = mergeSenders(
            listOf(
                InboxSender("VM-HDFCBK", 40, 30, 0),
                InboxSender("AD-HDFCBK", 10, 5, 0),
                InboxSender("+971501234567", 99, 9, 0),
                InboxSender("NOON", 50, 2, 0)
            )
        )
        assertEquals(listOf(Triple("HDFCBK", 50, true), Triple("NOON", 50, false)), senders.map { Triple(it.key, it.count, it.suggested) })
        assertEquals(listOf("VM-HDFCBK", "AD-HDFCBK"), senders[0].addresses)
    }

    @Test
    fun turnsChoicesIntoImportAndIgnoreRulesCollectingDigitsOnlyFromImportedKinds() {
        val groups = groupMessages(listOf(enbd("45.50", "1234", "CARREFOUR"), otp("111111")))
        val choices = initialChoices(groups)
        val g = groups[0]
        assertEquals(listOf("1234"), collectDigits(groups, choices).map { it.digits })

        val rules = buildRules(groups, choices, emptyMap())
        assertEquals(setOf(RuleAction.IGNORE, RuleAction.IMPORT), rules.map { it.action }.toSet())
        assertTrue(choices.getValue(kindId(g.senderKey, g.kinds[0].templateKey)).include)
        assertEquals(1, countIncluded(groups, choices))
        assertEquals(emptyList(), kindsWithoutDigits(groups, choices))
    }

    @Test
    fun cardChoicesAndPeriods() {
        assertFalse(cardChoiceComplete(null))
        assertFalse(cardChoiceComplete(CardChoice.New(" ", AccountKind.BANK, Currency.AED)))
        assertFalse(cardChoiceComplete(CardChoice.New("ENBD", null, Currency.AED)))
        assertTrue(cardChoiceComplete(CardChoice.New("ENBD", AccountKind.CREDIT_CARD, Currency.AED)))
        assertTrue(cardChoiceComplete(CardChoice.Skip))
        assertEquals(localTime(2026, 6, 31, 9), periodStart(3, localTime(2026, 9, 31, 9)))
    }
}
