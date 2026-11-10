package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

fun enbd(amount: String, digits: String, merchant: String, minutes: Int = 0) = RawSms(
    "EmiratesNBD",
    "Purchase of AED $amount with Credit Card ending $digits at $merchant. Avl Cr. Limit is AED 9,000.00",
    minutes * 60_000L
)

fun otp(code: String) = RawSms("EmiratesNBD", "$code is your OTP for online purchase of AED 20.00", 0)

val promo = RawSms("EmiratesNBD", "Get 10% cashback up to AED 200 on dining this weekend! Apply now.", 0)

class TemplatesTest {
    @Test
    fun mergesOperatorPrefixesAndSuffixes() {
        assertEquals(listOf("HDFCBK", "HDFCBK", "HDFCBK"), listOf("VM-HDFCBK", "AD-HDFCBK-S", " hdfcbk ").map(::senderKey))
    }

    @Test
    fun keysMessagesOfOneKindTogetherRegardlessOfAmountsCardsAndMerchants() {
        assertEquals(templateKey(enbd("45.50", "1234", "CARREFOUR").body), templateKey(enbd("1,200.00", "9876", "NOON").body))
        assertNotEquals(templateKey(enbd("45.50", "1234", "X").body), templateKey(otp("123456").body))
        assertEquals("purchase amount of aed # at * on", templateKey("Purchase amount of AED 12.00 at GLOBALFASHION.AE on 11/10"))
        assertEquals("your otp is #", templateKey("YOUR OTP IS 1234"))
    }

    @Test
    fun separatesDebitedAndCreditedMessagesThatShareAnOpening() {
        assertNotEquals(
            templateKey("Your A/c XX1234 has been debited with INR 50.00"),
            templateKey("Your A/c XX1234 has been credited with INR 50.00")
        )
    }

    @Test
    fun labelsTransactionsOtpsAndPromotions() {
        assertEquals(
            KindSuggestion(KindLabel.TRANSACTION, 0, Direction.DEBIT, Currency.AED),
            suggestKind(enbd("45.50", "1234", "CARREFOUR"))
        )
        assertEquals(KindLabel.OTP, suggestKind(otp("123456")).label)
        assertEquals(KindLabel.PROMO, suggestKind(promo).label)
        assertEquals(KindLabel.OTHER, suggestKind(RawSms("X", "Your card has been dispatched.", 0)).label)
        assertEquals(KindLabel.MONEY, suggestKind(RawSms("X", "Your bill of AED 20.00 is ready", 0)).label)
    }

    @Test
    fun groupsBySenderThenKindBiggestKindFirstWithCardDigits() {
        val groups = groupMessages(
            listOf(
                enbd("45.50", "1234", "CARREFOUR"),
                otp("111111"),
                enbd("12.00", "9876", "NOON", 5),
                enbd("3.00", "1234", "LULU", 9),
                promo
            )
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("EmiratesNBD"), groups[0].addresses)
        val kinds = groups[0].kinds
        assertEquals(3, kinds[0].messages.size)
        assertEquals(listOf("1234", "9876"), kinds[0].digits.sorted())
        assertEquals(listOf(KindLabel.OTP, KindLabel.PROMO), kinds.drop(1).map { it.suggestion.label }.sorted())
    }

    private val rule = SmsRule(
        id = "r",
        senderKey = "EMIRATESNBD",
        templateKey = templateKey(enbd("1", "1", "X").body),
        action = RuleAction.IMPORT,
        amountIndex = 0,
        direction = Direction.DEBIT,
        currency = Currency.AED,
        fixedAccountId = null,
        sample = ""
    )

    @Test
    fun readsNewMerchantsAndAmountsByPositionSkippingTheBalance() {
        val sms = enbd("1,250.75", "4321", "SHARAF DG")
        assertEquals(rule, ruleFor(sms, listOf(rule)))
        assertEquals(ParsedSms(125075, Currency.AED, Direction.DEBIT, "SHARAF DG", "4321", null, "rule:r"), applyRule(rule, sms))
        assertNull(ruleFor(otp("1"), listOf(rule)))
        assertNull(applyRule(rule.copy(action = RuleAction.IGNORE), sms))
        assertNull(applyRule(rule.copy(amountIndex = 2), sms))
    }

    @Test
    fun takesTheSecondAmountWhenTheUserPickedIt() {
        assertEquals(900000L, applyRule(rule.copy(amountIndex = 1), enbd("1.00", "4321", "X"))?.amountMinor)
    }
}
