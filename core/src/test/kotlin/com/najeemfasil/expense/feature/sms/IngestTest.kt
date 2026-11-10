package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.feature.categories.MerchantRule
import com.najeemfasil.expense.feature.transactions.DedupeCandidate
import com.najeemfasil.expense.feature.transactions.DedupeIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val t0 = localTime(2026, 9, 11, 13, 0)
private const val HDFC = "Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nRef 123"

private fun rule(
    id: String = "r1",
    templateKey: String = templateKey(HDFC),
    action: RuleAction = RuleAction.IMPORT,
    fixedAccountId: String? = null
) = SmsRule(id, senderKey("VM-HDFCBK"), templateKey, action, 0, Direction.DEBIT, Currency.INR, fixedAccountId, HDFC)

private val accounts = listOf(AccountRef("hdfc", Currency.INR, "5678"), AccountRef("enbd", Currency.AED, "1234"))

private fun ctx(smsRules: List<SmsRule> = listOf(rule()), dedupe: DedupeIndex = DedupeIndex()) = IngestContext(smsRules, emptyList(), accounts, dedupe, listOf(MerchantRule("SWIGGY", "food")))

private fun draft(r: IngestResult) = assertIs<IngestResult.Draft>(r).draft

class IngestTest {
    @Test
    fun turnsAConfiguredKindIntoADraftWithCardCategoryAndSmsTime() {
        val sms = RawSms("VM-HDFCBK", HDFC, t0)
        val d = draft(processSms(sms, ctx()))
        assertEquals(
            TransactionDraft(50000, Currency.INR, Direction.DEBIT, "SWIGGY", t0, "hdfc", "5678", "food", null, smsHash(sms), "VM-HDFCBK", HDFC, "rule:r1"),
            d
        )
    }

    @Test
    fun matchesTheSameBankUnderAnotherOperatorPrefix() {
        assertEquals(7500L, draft(processSms(RawSms("AD-HDFCBK-S", HDFC.replace("500.00", "75.00"), t0), ctx())).amountMinor)
    }

    @Test
    fun ignoresARealLookingTransactionFromASenderThatWasNotSetUp() {
        val body = "Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR, DUBAI."
        assertEquals(IngestResult.Ignored("Not a configured bank SMS"), processSms(RawSms("EmiratesNBD", body, t0), ctx()))
    }

    @Test
    fun ignoresKindsTheUserChoseToIgnore() {
        val otp = "482913 is your OTP for txn of Rs 500.00 at AMAZON. Do not share."
        val c = ctx(listOf(rule(), rule(id = "r2", templateKey = templateKey(otp), action = RuleAction.IGNORE)))
        assertEquals(IngestResult.Ignored("You chose to ignore this kind of message"), processSms(RawSms("VM-HDFCBK", otp, t0), c))
    }

    @Test
    fun flagsUnknownKindsWithAmountsFromAConfiguredBankAsUnreadButNotOtps() {
        val odd = "Your HDFC card statement total is Rs 12,000.00. Pay by 20th."
        assertEquals(IngestResult.Unparsed, processSms(RawSms("VM-HDFCBK", odd, t0), ctx()))
        assertEquals(IngestResult.Ignored("Not a transaction"), processSms(RawSms("VM-HDFCBK", "111111 is your OTP for Rs 99.00", t0), ctx()))
    }

    @Test
    fun flagsASecondSmsForTheSamePaymentAsADuplicate() {
        val dedupe = DedupeIndex(listOf(DedupeCandidate("tx1", 50000, Currency.INR, Direction.DEBIT, t0 - 2 * 60_000, "hdfc")))
        assertEquals("tx1", draft(processSms(RawSms("VM-HDFCBK", HDFC, t0), ctx(dedupe = dedupe))).duplicateOfId)
    }

    @Test
    fun usesTheFixedAccountForKindsThatNeverNameACard() {
        val body = "Rs 250.00 spent on your card at ZOMATO."
        val c = ctx(listOf(rule(templateKey = templateKey(body), fixedAccountId = "enbd")))
        assertEquals("enbd", draft(processSms(RawSms("VM-HDFCBK", body, t0), c)).accountId)
    }

    @Test
    fun keepsTheSmsDateOnlyWhenItIsAnotherDay() {
        val body = "Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nOn 09/10/26\nRef 123"
        val c = ctx(listOf(rule(templateKey = templateKey(body))))
        assertEquals(localTime(2026, 9, 9), draft(processSms(RawSms("VM-HDFCBK", body, t0), c)).occurredAt)
        val sameDay = body.replace("09/10/26", "11/10/26")
        assertEquals(t0, draft(processSms(RawSms("VM-HDFCBK", sameDay, t0), c)).occurredAt)
    }

    @Test
    fun usesDigitsFirst() = assertEquals("enbd", matchAccount("1234", Currency.INR, accounts))

    @Test
    fun leavesUnknownDigitsUnlinkedInsteadOfGuessing() = assertNull(matchAccount("9999", Currency.AED, accounts))

    @Test
    fun fallsBackToTheOnlyAccountInThatCurrency() = assertEquals("enbd", matchAccount(null, Currency.AED, accounts))

    @Test
    fun givesUpWhenAmbiguous() = assertNull(matchAccount(null, Currency.INR, accounts + AccountRef("sbi", Currency.INR, null)))
}
