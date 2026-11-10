package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.data.db.resetAllData
import com.najeemfasil.expense.data.db.testDb
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.createAccount
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.feature.accounts.listDetectedCards
import com.najeemfasil.expense.feature.categories.MerchantRule
import com.najeemfasil.expense.feature.categories.listRules
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.TransactionStatus
import com.najeemfasil.expense.feature.transactions.approveAllPending
import com.najeemfasil.expense.feature.transactions.listPending
import com.najeemfasil.expense.feature.transactions.listSummaryInputs
import com.najeemfasil.expense.feature.transactions.saveTransaction
import com.najeemfasil.expense.feature.transactions.setStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val t0 = localTime(2026, 9, 11, 13, 0)

private fun card(amount: String, digits: String, minutes: Int = 0) = RawSms(
    "EmiratesNBD",
    "Purchase of AED $amount with Credit Card ending $digits at CARREFOUR, DUBAI. Avl Cr. Limit is AED 9,000.00",
    t0 + minutes * 60_000L
)

/** Sets up ENBD card purchases as an imported kind, as SMS setup would. */
private fun configureEnbd(db: Conn) {
    val sample = card("1", "1").body
    replaceSmsRules(
        db,
        listOf("EMIRATESNBD"),
        listOf(SmsRule("", "EMIRATESNBD", templateKey(sample), RuleAction.IMPORT, 0, Direction.DEBIT, Currency.AED, null, sample))
    )
}

private fun all(db: Conn) = listSummaryInputs(db, 0, Long.MAX_VALUE)

class SmsRepositoryTest {
    @Test
    fun importsNothingUntilSmsIsConfigured() {
        val db = testDb()
        assertEquals(ImportSummary(read = 1, ignored = 1), importBatch(db, listOf(card("45.50", "1234"))))
        assertEquals(0, listPending(db).size)
    }

    @Test
    fun importsConfiguredKindsFlagsDuplicatesAndNeverReImportsTheSameSms() {
        val db = testDb()
        configureEnbd(db)
        val batch = listOf(card("45.50", "1234"), card("45.50", "1234", 2), card("10.00", "1234", 30))
        assertEquals(ImportSummary(read = 3, added = 3, duplicates = 1), importBatch(db, batch))
        assertEquals(ImportSummary(read = 3, ignored = 3), importBatch(db, batch))
        assertEquals(3, listPending(db).size)
        assertEquals(IngestResult.AlreadyImported, importSms(db, batch[0]))
    }

    @Test
    fun acceptsSetupImportsStraightIntoTotalsButKeepsDuplicatesForReview() {
        val db = testDb()
        configureEnbd(db)
        val batch = listOf(card("45.50", "1234"), card("45.50", "1234", 1), card("12.00", "1234", 300))
        assertEquals(ImportSummary(read = 3, added = 3, accepted = 2, duplicates = 1), importBatch(db, batch, autoAccept = true))
        assertEquals(1, listPending(db).size)
        assertEquals(5750L, all(db).sumOf { it.amountMinor })
    }

    @Test
    fun approvesAllWithOrWithoutPossibleDuplicates() {
        val db = testDb()
        configureEnbd(db)
        importBatch(db, listOf(card("45.50", "1234"), card("45.50", "1234", 1), card("12.00", "1234", 300)))
        assertEquals(2, approveAllPending(db, false))
        assertEquals(1, listPending(db).size)
        assertEquals(1, approveAllPending(db, true))
        assertEquals(0, listPending(db).size)
    }

    @Test
    fun clearsSmsTransactionsButKeepsManualOnes() {
        val db = testDb()
        configureEnbd(db)
        importBatch(db, listOf(card("45.50", "1234"), card("9.00", "1234", 60)))
        saveTransaction(db, null, TransactionInput(1000, Currency.AED, Direction.DEBIT, "Cash", null, t0, null, null))
        assertEquals(2, countSmsTransactions(db))
        assertEquals(2, clearSmsTransactions(db))
        assertEquals(listOf(1000L), all(db).map { it.amountMinor })
    }

    @Test
    fun detectsUnknownCardsAndLinksPastTransactionsWhenTheCardIsAdded() {
        val db = testDb()
        configureEnbd(db)
        importBatch(db, listOf(card("45.50", "1234"), card("12.00", "1234", 60), card("5.00", "7777", 120)))
        assertEquals(
            listOf(Triple("1234", 2, "EmiratesNBD"), Triple("7777", 1, "EmiratesNBD")),
            listDetectedCards(db).map { Triple(it.digits, it.count, it.lastSender) }
        )
        assertEquals(2, createAccount(db, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234"))
        val a = listAccounts(db)[0]
        assertEquals(AccountKind.CREDIT_CARD to "1234", a.kind to a.smsHint)
        assertEquals(listOf("7777"), listDetectedCards(db).map { it.digits })
        assertNotNull(assertIs<IngestResult.Draft>(importSms(db, card("3.00", "1234", 300))).draft.accountId)
    }

    @Test
    fun resetsToAFreshDatabase() {
        val db = testDb()
        createAccount(db, "ENBD", AccountKind.CREDIT_CARD, Currency.AED, "1234")
        saveTransaction(db, null, TransactionInput(500, Currency.AED, Direction.DEBIT, "Carrefour", null, 0, null, "groceries"))
        resetAllData(db)
        assertEquals(emptyList(), listAccounts(db))
        assertEquals(emptyList(), all(db))
        assertEquals(listOf(MerchantRule("ASPORA", "remittance")), listRules(db))
        assertTrue((db.first("SELECT COUNT(*) FROM categories") { it.int(0) } ?: 0) > 10)
    }

    @Test
    fun learnsCategoriesOnAcceptAndAppliesThemToTheNextSms() {
        val db = testDb()
        configureEnbd(db)
        importSms(db, card("45.50", "1234"))
        val tx = listPending(db).single()
        saveTransaction(
            db,
            tx.id,
            TransactionInput(tx.amountMinor, tx.currency, tx.direction, tx.merchant, tx.note, tx.occurredAt, tx.accountId, "groceries")
        )
        assertTrue(listRules(db).containsAll(listOf(MerchantRule("ASPORA", "remittance"), MerchantRule("CARREFOUR", "groceries"))))

        val next = assertIs<IngestResult.Draft>(importSms(db, card("8.00", "1234", 120)))
        assertEquals("groceries", next.draft.categoryId)
        setStatus(db, listOf(listPending(db)[0].id), TransactionStatus.ACCEPTED)
        assertEquals(0, listPending(db).size)
    }

    @Test
    fun keepsUnknownKindsFromConfiguredBanksAndReReadsThemAfterAFormatIsSetUp() {
        val db = testDb()
        configureEnbd(db)
        val odd = RawSms("EmiratesNBD", "Card 4455 used. Amt: 87.25 AED. Shop: LULU.", t0)
        assertEquals(IngestResult.Unparsed, importSms(db, odd))
        assertEquals(1, listUnread(db).size)

        val start = odd.body.indexOf("87.25")
        val built = assertIs<BuildResult.Ok>(buildPattern(odd.body, listOf(FieldMark(FieldName.AMOUNT, start, start + 5))))
        createCustomParser(db, CustomParser("", "ENBD other", "EmiratesNBD", built.pattern, built.fields, Currency.AED, Direction.DEBIT), odd.body)
        assertEquals(listOf(FieldName.AMOUNT), listCustomParsers(db).single().parser.fields)
        assertEquals("[\"amount\"]", db.first("SELECT fields FROM custom_parsers") { it.text(0) })

        assertEquals(1, recheckUnread(db).added)
        assertEquals(0, listUnread(db).size)
        val p = listPending(db)[0]
        assertEquals(8725L to "EmiratesNBD", p.amountMinor to p.smsSender)
    }

    @Test
    fun replacesRulesOnlyForTheGivenSendersAndDismissesUnread() {
        val db = testDb()
        configureEnbd(db)
        val hdfc = "Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY"
        replaceSmsRules(db, listOf("HDFCBK"), listOf(SmsRule("", "HDFCBK", templateKey(hdfc), RuleAction.IGNORE, 0, Direction.DEBIT, Currency.INR, null, hdfc)))
        assertEquals(listOf("EMIRATESNBD", "HDFCBK"), listSmsRules(db).map { it.senderKey })
        replaceSmsRules(db, listOf("EMIRATESNBD"), emptyList())
        val left = listSmsRules(db).single()
        deleteSmsRule(db, left.id)
        assertEquals(emptyList(), listSmsRules(db))

        configureEnbd(db)
        val odd = RawSms("EmiratesNBD", "Card 4455 used. Amt: 87.25 AED.", t0)
        importSms(db, odd)
        dismissUnread(db, smsHash(odd))
        assertEquals(emptyList(), listUnread(db))
        deleteCustomParser(db, "none")
    }

    @Test
    fun imports5000SmsQuicklyWithCorrectDuplicateFlags() {
        val db = testDb()
        configureEnbd(db)
        val batch = ArrayList<RawSms>()
        for (i in 0 until 2500) {
            val amount = "${i + 1}.00"
            batch.add(card(amount, "1234", i * 60))
            batch.add(card(amount, "1234", i * 60 + 1))
        }
        var chunks = 0
        val started = System.currentTimeMillis()
        val s = importBatch(db, batch, autoAccept = true) { chunks++ }
        val took = System.currentTimeMillis() - started
        assertTrue(took < 15_000, "took $took ms")
        assertEquals(ImportSummary(read = 5000, added = 5000, accepted = 2500, duplicates = 2500), s)
        assertEquals(25, chunks)
    }
}
