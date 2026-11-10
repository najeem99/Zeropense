package com.najeemfasil.expense.feature.transactions

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.DAY_MS
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.newId
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.data.db.Row
import com.najeemfasil.expense.feature.categories.learnRule
import com.najeemfasil.expense.feature.dashboard.SummaryInput
import com.najeemfasil.expense.feature.sms.smsSnippet

enum class TransactionStatus(val db: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    companion object {
        fun from(value: String): TransactionStatus = entries.firstOrNull { it.db == value } ?: PENDING
    }
}

enum class TransactionSource(val db: String) {
    SMS("sms"),
    MANUAL("manual");

    companion object {
        fun from(value: String): TransactionSource = if (value == "sms") SMS else MANUAL
    }
}

data class Transaction(
    val id: String,
    val status: TransactionStatus,
    val source: TransactionSource,
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val merchant: String?,
    val note: String?,
    val occurredAt: Long,
    val accountId: String?,
    /** Last digits named in the SMS; set even when no account matches yet. */
    val accountHint: String?,
    val categoryId: String?,
    val duplicateOfId: String?,
    val smsSender: String?,
    val smsBody: String?
)

private const val COLUMNS =
    "id, status, source, amount_minor, currency, direction, merchant, note, occurred_at, account_id, account_hint, " +
        "category_id, duplicate_of_id, sms_sender, sms_body"

private fun transaction(r: Row) = Transaction(
    id = r.text(0),
    status = TransactionStatus.from(r.text(1)),
    source = TransactionSource.from(r.text(2)),
    amountMinor = r.long(3),
    currency = Currency.from(r.text(4)) ?: Currency.AED,
    direction = Direction.from(r.text(5)) ?: Direction.DEBIT,
    merchant = r.textOrNull(6),
    note = r.textOrNull(7),
    occurredAt = r.long(8),
    accountId = r.textOrNull(9),
    accountHint = r.textOrNull(10),
    categoryId = r.textOrNull(11),
    duplicateOfId = r.textOrNull(12),
    smsSender = r.textOrNull(13),
    smsBody = r.textOrNull(14)
)

fun getTransaction(db: Conn, id: String): Transaction? = db.first("SELECT $COLUMNS FROM transactions WHERE id = ?", id, map = ::transaction)

fun listPending(db: Conn): List<Transaction> = db.query("SELECT $COLUMNS FROM transactions WHERE status = 'pending' ORDER BY occurred_at DESC", map = ::transaction)

fun countPending(db: Conn): Int = db.first("SELECT COUNT(*) FROM transactions WHERE status = 'pending'") { it.int(0) } ?: 0

/** Accepted transactions in [from, to). */
fun listAccepted(db: Conn, from: Long, to: Long, limit: Int = 1000): List<Transaction> = db.query(
    """SELECT $COLUMNS FROM transactions
       WHERE status = 'accepted' AND occurred_at >= ? AND occurred_at < ?
       ORDER BY occurred_at DESC LIMIT ?""",
    from,
    to,
    limit,
    map = ::transaction
)

/** Amount fields of every accepted transaction in [from, to), for totals (no row limit). */
fun listSummaryInputs(db: Conn, from: Long, to: Long): List<SummaryInput> = db.query(
    """SELECT amount_minor, currency, direction, category_id, occurred_at
       FROM transactions
       WHERE status = 'accepted' AND occurred_at >= ? AND occurred_at < ?""",
    from,
    to
) {
    SummaryInput(
        amountMinor = it.long(0),
        currency = Currency.from(it.text(1)) ?: Currency.AED,
        direction = Direction.from(it.text(2)) ?: Direction.DEBIT,
        categoryId = it.textOrNull(3),
        occurredAt = it.long(4)
    )
}

/** Non-rejected transactions between `from` and `to` widened by a day, for duplicate detection. */
fun listForDedupe(db: Conn, from: Long, to: Long): List<DedupeCandidate> = db.query(
    """SELECT id, amount_minor, currency, direction, occurred_at, account_id
       FROM transactions WHERE status != 'rejected' AND occurred_at BETWEEN ? AND ?""",
    from - DAY_MS,
    to + DAY_MS
) {
    DedupeCandidate(
        id = it.text(0),
        amountMinor = it.long(1),
        currency = Currency.from(it.text(2)) ?: Currency.AED,
        direction = Direction.from(it.text(3)) ?: Direction.DEBIT,
        occurredAt = it.long(4),
        accountId = it.textOrNull(5)
    )
}

data class TransactionInput(
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val merchant: String?,
    val note: String?,
    val occurredAt: Long,
    val accountId: String?,
    val categoryId: String?
)

/**
 * Creates a manual transaction (id null) or updates one. Saving always accepts it,
 * and teaches the categorizer the merchant → category choice.
 */
fun saveTransaction(db: Conn, id: String?, input: TransactionInput): String {
    val now = System.currentTimeMillis()
    val txId = id ?: newId()
    db.transaction {
        if (id != null) {
            db.run(
                """UPDATE transactions SET status = 'accepted', amount_minor = ?, currency = ?, direction = ?, merchant = ?,
                     note = ?, occurred_at = ?, account_id = ?, category_id = ?, updated_at = ? WHERE id = ?""",
                input.amountMinor, input.currency.name, input.direction.db, input.merchant, input.note,
                input.occurredAt, input.accountId, input.categoryId, now, id
            )
        } else {
            db.run(
                """INSERT INTO transactions (id, status, source, amount_minor, currency, direction, merchant, note,
                     occurred_at, account_id, category_id, created_at, updated_at)
                   VALUES (?, 'accepted', 'manual', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                txId, input.amountMinor, input.currency.name, input.direction.db, input.merchant, input.note,
                input.occurredAt, input.accountId, input.categoryId, now, now
            )
        }
        learnRule(db, input.merchant, input.categoryId)
    }
    return txId
}

private const val CHUNK = 500

/** Learns merchant → category from the given transactions (only rows that have both). */
private fun learnFrom(db: Conn, where: String, params: List<Any?>) {
    val pairs = db.query(
        """SELECT DISTINCT merchant, category_id FROM transactions
           WHERE $where AND merchant IS NOT NULL AND category_id IS NOT NULL""",
        *params.toTypedArray()
    ) { it.text(0) to it.text(1) }
    for ((merchant, categoryId) in pairs) learnRule(db, merchant, categoryId)
}

fun setStatus(db: Conn, ids: List<String>, status: TransactionStatus) {
    if (ids.isEmpty()) return
    db.transaction {
        for (chunk in ids.chunked(CHUNK)) {
            val marks = chunk.joinToString(",") { "?" }
            db.run(
                "UPDATE transactions SET status = ?, updated_at = ? WHERE id IN ($marks)",
                status.db,
                System.currentTimeMillis(),
                *chunk.toTypedArray()
            )
            if (status == TransactionStatus.ACCEPTED) learnFrom(db, "id IN ($marks)", chunk)
        }
    }
}

/** Accepts the whole Review queue in one statement. Returns how many were approved. */
fun approveAllPending(db: Conn, includeDuplicates: Boolean): Int {
    val where = if (includeDuplicates) "status = 'pending'" else "status = 'pending' AND duplicate_of_id IS NULL"
    return db.transaction {
        learnFrom(db, where, emptyList())
        db.run("UPDATE transactions SET status = 'accepted', updated_at = ? WHERE $where", System.currentTimeMillis())
    }
}

/** Manual entries are deleted; SMS entries become rejected so the SMS isn't re-imported. */
fun removeTransaction(db: Conn, tx: Transaction) {
    if (tx.source == TransactionSource.SMS) {
        setStatus(db, listOf(tx.id), TransactionStatus.REJECTED)
    } else {
        db.run("DELETE FROM transactions WHERE id = ?", tx.id)
    }
}

/** What a row is called, and whether it is a stand-in for a missing merchant (shown smaller). */
data class TransactionTitle(val text: String, val isSnippet: Boolean)

/** The merchant, else a short snippet of the SMS, else the direction. */
fun transactionTitle(merchant: String?, smsBody: String?, direction: Direction): TransactionTitle {
    if (!merchant.isNullOrEmpty()) return TransactionTitle(merchant, false)
    if (!smsBody.isNullOrEmpty()) return TransactionTitle(smsSnippet(smsBody), true)
    return TransactionTitle(if (direction == Direction.CREDIT) "Money in" else "Money out", true)
}
