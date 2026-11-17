package com.najeemfasil.expense.feature.cards

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.DAY_MS
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.feature.accounts.Account
import com.najeemfasil.expense.feature.accounts.AccountKind
import com.najeemfasil.expense.feature.accounts.accountLabel
import com.najeemfasil.expense.feature.categories.CARD_PAYMENT_CATEGORY
import com.najeemfasil.expense.feature.transactions.TransactionInput
import com.najeemfasil.expense.feature.transactions.saveTransaction
import kotlin.math.abs

/**
 * Credit card bill payments: money paid to a credit card, usually from a bank account. They are
 * transactions in the "Credit card payment" category, which never count as spending (the card
 * purchases already did). This file works out which card each payment went to and the totals per card.
 */
data class CardPaymentTx(
    val id: String,
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val occurredAt: Long,
    val accountId: String?,
    val accountHint: String?,
    /** The card the user linked it to, if any. */
    val paidCardId: String?,
    val merchant: String?,
    val smsBody: String?
)

/** Every accepted, not ignored credit card payment, newest first. */
fun listCardPaymentTxs(db: Conn): List<CardPaymentTx> = db.query(
    """SELECT id, amount_minor, currency, direction, occurred_at, account_id, account_hint, paid_card_id, merchant, sms_body
       FROM transactions
       WHERE status = 'accepted' AND excluded = 0 AND category_id = ?
       ORDER BY occurred_at DESC""",
    CARD_PAYMENT_CATEGORY
) {
    CardPaymentTx(
        id = it.text(0),
        amountMinor = it.long(1),
        currency = Currency.from(it.text(2)) ?: Currency.AED,
        direction = Direction.from(it.text(3)) ?: Direction.DEBIT,
        occurredAt = it.long(4),
        accountId = it.textOrNull(5),
        accountHint = it.textOrNull(6),
        paidCardId = it.textOrNull(7),
        merchant = it.textOrNull(8),
        smsBody = it.textOrNull(9)
    )
}

fun creditCards(accounts: List<Account>): List<Account> = accounts.filter { it.kind == AccountKind.CREDIT_CARD }

/** How the card of a payment was found. */
enum class CardMatch { LINKED, CARD_SMS, DIGITS, NONE }

private fun mentions(text: String?, digits: String): Boolean = !text.isNullOrEmpty() && Regex("(?<!\\d)${Regex.escape(digits)}(?!\\d)").containsMatchIn(text)

/**
 * The card a payment went to: the one the user picked; else the card itself when the SMS came
 * from the card ("payment received"); else the only credit card whose last digits the SMS names
 * (other than the paying account's own digits). Null when unknown or ambiguous.
 */
fun resolveCard(tx: CardPaymentTx, cards: List<Account>): Pair<String?, CardMatch> {
    if (tx.paidCardId != null && cards.any { it.id == tx.paidCardId }) return tx.paidCardId to CardMatch.LINKED
    if (tx.accountId != null && cards.any { it.id == tx.accountId }) return tx.accountId to CardMatch.CARD_SMS
    val own = tx.accountHint?.takeLast(4)
    val named = cards.filter { c ->
        val hint = c.smsHint?.takeLast(4)
        !hint.isNullOrEmpty() && hint != own && (mentions(tx.smsBody, hint) || mentions(tx.merchant, hint))
    }
    return if (named.size == 1) named[0].id to CardMatch.DIGITS else null to CardMatch.NONE
}

/** One bill payment to a card, after pairing the bank's debit SMS with the card's "payment received" SMS. */
data class CardPayment(
    val txId: String,
    val cardId: String?,
    val match: CardMatch,
    val amountMinor: Long,
    val currency: Currency,
    val occurredAt: Long,
    /** The account the money left, when known. */
    val fromAccountId: String?,
    /** The card's own "payment received" transaction, when both SMS were imported. */
    val confirmedById: String?
)

/** Two SMS about one payment can be this far apart. */
private const val PAIR_WINDOW_MS = 3 * DAY_MS

/**
 * The payments, newest first. A bank debit and the card's credit for the same amount within three
 * days are one payment (counted once).
 */
fun cardPayments(txs: List<CardPaymentTx>, cards: List<Account>): List<CardPayment> {
    val resolved = txs.map { it to resolveCard(it, cards) }
    val cardSide = resolved.filter { (t, r) -> r.second == CardMatch.CARD_SMS && t.direction == Direction.CREDIT }
    val used = HashSet<String>()
    val out = ArrayList<CardPayment>()
    for ((t, r) in resolved) {
        if (r.second == CardMatch.CARD_SMS && t.direction == Direction.CREDIT) continue
        val cardId = r.first
        val partner = if (cardId == null) {
            null
        } else {
            cardSide.firstOrNull { (c, cr) ->
                c.id !in used &&
                    cr.first == cardId &&
                    c.amountMinor == t.amountMinor &&
                    c.currency == t.currency &&
                    abs(c.occurredAt - t.occurredAt) <= PAIR_WINDOW_MS
            }?.first
        }
        partner?.let { used.add(it.id) }
        out.add(
            CardPayment(
                txId = t.id,
                cardId = cardId,
                match = r.second,
                amountMinor = t.amountMinor,
                currency = t.currency,
                occurredAt = t.occurredAt,
                fromAccountId = t.accountId?.takeIf { id -> cards.none { it.id == id } },
                confirmedById = partner?.id
            )
        )
    }
    for ((t, r) in cardSide) {
        if (t.id in used) continue
        out.add(CardPayment(t.id, r.first, r.second, t.amountMinor, t.currency, t.occurredAt, null, null))
    }
    return out.sortedByDescending { it.occurredAt }
}

data class CardTotal(
    val currency: Currency,
    val totalMinor: Long,
    val count: Int,
    val lastAt: Long?
)

/** What was paid to one card, per currency (a card is normally paid in one currency). */
fun cardTotals(payments: List<CardPayment>, cardId: String): List<CardTotal> = payments.filter { it.cardId == cardId }
    .groupBy { it.currency }
    .map { (cur, list) -> CardTotal(cur, list.sumOf { it.amountMinor }, list.size, list.maxOfOrNull { it.occurredAt }) }
    .sortedByDescending { it.totalMinor }

/** Records a payment made outside the SMS flow (e.g. cash at the branch). Returns the transaction id. */
fun recordCardPayment(
    db: Conn,
    card: Account,
    amountMinor: Long,
    occurredAt: Long,
    fromAccountId: String?,
    note: String?
): String = saveTransaction(
    db,
    null,
    TransactionInput(
        amountMinor = amountMinor,
        currency = card.currency,
        direction = Direction.DEBIT,
        merchant = "${accountLabel(card)} payment",
        note = note,
        occurredAt = occurredAt,
        accountId = fromAccountId,
        categoryId = CARD_PAYMENT_CATEGORY,
        paidCardId = card.id
    )
)
