package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.feature.sms.setup.buildRules
import com.najeemfasil.expense.feature.sms.setup.initialChoices
import com.najeemfasil.expense.feature.transactions.DedupeIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The user's real bank SMS (ENBD-style, UAE). Each must be read correctly end to end. */
private object Samples {
    const val PURCHASE = "Purchase of AED 12.00 with Credit Card ending 9157 at GLOBALFASHION.AE, +971521253808. Avl Cr. Limit is AED 6,988.22"
    const val REFUND = "Purchase amount of AED 12.00 at GLOBALFASHION.AE on your Credit Card ending 9157 has been refunded to your card account. Avl Limit is AED 7,000.22."
    const val FUEL = " Credit Card Purchase\nCard Ending: 1122\nAt: ADNOC AL BUHAIRA 176, SHARJAH\nAmount: AED 124.52\nDate: 15/10/2026, 19:12\nAvailable Limit: AED 6,754.45"
    const val SALARY = " Salary Deposited \nAmount: AED 1234.00\nAccount: 370XXX08XXX01\nDate: 26/09/2026 11:39 \nAvailable Balance: AED 1234.60"
    const val TRANSFER = "AED 500.00 has been credited to your account XXX4567 from JOHN SMITH via Aani. Avl Bal AED 2,000.00"
    val ALL = listOf(PURCHASE, REFUND, FUEL, SALARY, TRANSFER)
}

private val at = localTime(2026, 9, 16, 12, 0)

private fun msg(body: String, minutes: Int = 0) = RawSms("EmiratesNBD", body, at + minutes * 60_000L)

/** What the setup would save if the user accepted its suggestions for these messages. */
private fun setupRules(bodies: List<String>): List<SmsRule> {
    val groups = groupMessages(bodies.mapIndexed { i, b -> msg(b, i) })
    return buildRules(groups, initialChoices(groups), emptyMap()).mapIndexed { i, r -> r.copy(id = "r$i") }
}

private val rules = setupRules(Samples.ALL)

private fun read(body: String): TransactionDraft {
    val ctx = IngestContext(
        rules,
        emptyList(),
        listOf(AccountRef("card9157", Currency.AED, "9157"), AccountRef("card1122", Currency.AED, "1122")),
        DedupeIndex(),
        emptyList()
    )
    return assertIs<IngestResult.Draft>(processSms(msg(body, 999), ctx)).draft
}

class RealSamplesTest {
    @Test
    fun readsACardPurchaseKeepingTheDotInTheMerchantName() {
        val d = read(Samples.PURCHASE)
        assertEquals(listOf(1200L, Direction.DEBIT, "GLOBALFASHION.AE", "card9157"), listOf(d.amountMinor, d.direction, d.merchant, d.accountId))
    }

    @Test
    fun readsARefundAsMoneyInInTheRefundCategory() {
        val d = read(Samples.REFUND)
        assertEquals(listOf(1200L, Direction.CREDIT, "card9157", "refund"), listOf(d.amountMinor, d.direction, d.accountId, d.categoryId))
    }

    @Test
    fun readsACreditCardPurchaseAsMoneyOutWithTheLabelledMerchantAndCard() {
        val d = read(Samples.FUEL)
        assertEquals(
            listOf(12452L, Direction.DEBIT, "ADNOC AL BUHAIRA 176", "1122", "card1122"),
            listOf(d.amountMinor, d.direction, d.merchant, d.accountHint, d.accountId)
        )
    }

    @Test
    fun readsSalaryAsIncomeNamedSalaryInTheSalaryCategory() {
        val d = read(Samples.SALARY)
        assertEquals(listOf(123400L, Direction.CREDIT, "Salary", "salary"), listOf(d.amountMinor, d.direction, d.merchant, d.categoryId))
    }

    @Test
    fun readsMoneyOtherPeopleSendAsIncomeInMoneyReceived() {
        val d = read(Samples.TRANSFER)
        assertEquals(listOf(50000L, Direction.CREDIT, "JOHN SMITH", "transfer-in"), listOf(d.amountMinor, d.direction, d.merchant, d.categoryId))
    }

    @Test
    fun groupsRefundsFromDifferentShopsAsOneKind() {
        val other = Samples.REFUND.replace("GLOBALFASHION.AE", "NOON.COM")
        assertEquals(templateKey(Samples.REFUND), templateKey(other))
        val d = read(other)
        assertEquals(Direction.CREDIT to "refund", d.direction to d.categoryId)
    }

    @Test
    fun suggestsAllFiveKindsAsTransactionsInTheSetup() {
        val groups = groupMessages(Samples.ALL.map { msg(it) })
        assertTrue(groups[0].kinds.all { it.suggestion.label == KindLabel.TRANSACTION })
        assertEquals(5, groups[0].kinds.size)
    }
}
