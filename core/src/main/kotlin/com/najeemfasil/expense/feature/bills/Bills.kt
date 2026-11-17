package com.najeemfasil.expense.feature.bills

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.addMonths
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.localTime
import com.najeemfasil.expense.core.newId
import com.najeemfasil.expense.core.startOfDay
import com.najeemfasil.expense.core.startOfMonth
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.feature.categories.merchantKey
import java.util.Calendar
import kotlin.math.abs

/**
 * Monthly bills (rent, landline, school fees) and EMIs (car loan, phone instalments). Each has an
 * amount and a day of the month it is due. A month counts as paid when a payment to the bill's
 * merchant shows up that month, or when the user marks it paid. An EMI also has a number of
 * instalments, so it shows how much is paid and how much is left.
 */
enum class BillKind(val db: String, val label: String) {
    BILL("bill", "Monthly bill"),
    EMI("emi", "EMI / loan");

    companion object {
        fun from(value: String): BillKind = if (value == "emi") EMI else BILL
    }
}

data class Bill(
    val id: String,
    val name: String,
    val kind: BillKind,
    val amountMinor: Long,
    val currency: Currency,
    val dueDay: Int,
    val categoryId: String?,
    /** Payments to this merchant (see [merchantKey]) mark a month paid. */
    val merchantKey: String?,
    /** First month tracked. */
    val startMonth: Long,
    /** EMI: total instalments. Null for an open-ended bill. */
    val installments: Int?,
    /** EMI: instalments already paid before [startMonth]. */
    val paidBefore: Int
)

data class BillInput(
    val name: String,
    val kind: BillKind,
    val amountMinor: Long,
    val currency: Currency,
    val dueDay: Int,
    val categoryId: String?,
    /** A merchant name as it appears on transactions; stored as its key. */
    val merchant: String?,
    val installments: Int?,
    val paidBefore: Int,
    val startMonth: Long
)

private const val BILL_COLUMNS = "id, name, kind, amount_minor, currency, due_day, category_id, merchant_key, start_month, installments, paid_before"

private fun bill(r: com.najeemfasil.expense.data.db.Row) = Bill(
    id = r.text(0),
    name = r.text(1),
    kind = BillKind.from(r.text(2)),
    amountMinor = r.long(3),
    currency = Currency.from(r.text(4)) ?: Currency.AED,
    dueDay = r.int(5),
    categoryId = r.textOrNull(6),
    merchantKey = r.textOrNull(7),
    startMonth = r.long(8),
    installments = r.longOrNull(9)?.toInt(),
    paidBefore = r.int(10)
)

fun listBills(db: Conn): List<Bill> = db.query("SELECT $BILL_COLUMNS FROM bills ORDER BY kind, due_day, name", map = ::bill)

fun getBill(db: Conn, id: String): Bill? = db.first("SELECT $BILL_COLUMNS FROM bills WHERE id = ?", id, map = ::bill)

/** Creates (id null) or updates a bill. Returns its id. */
fun saveBill(db: Conn, id: String?, input: BillInput): String {
    val billId = id ?: newId()
    val key = input.merchant?.let { merchantKey(it) }?.ifEmpty { null }
    val installments = if (input.kind == BillKind.EMI) input.installments else null
    val paidBefore = if (input.kind == BillKind.EMI) input.paidBefore.coerceAtLeast(0) else 0
    val day = input.dueDay.coerceIn(1, 31)
    if (id == null) {
        db.run(
            """INSERT INTO bills (id, name, kind, amount_minor, currency, due_day, category_id, merchant_key, start_month,
                 installments, paid_before, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            billId, input.name.jsTrim(), input.kind.db, input.amountMinor, input.currency.name, day, input.categoryId, key,
            startOfMonth(input.startMonth), installments, paidBefore, System.currentTimeMillis()
        )
    } else {
        db.run(
            """UPDATE bills SET name = ?, kind = ?, amount_minor = ?, currency = ?, due_day = ?, category_id = ?,
                 merchant_key = ?, start_month = ?, installments = ?, paid_before = ? WHERE id = ?""",
            input.name.jsTrim(), input.kind.db, input.amountMinor, input.currency.name, day, input.categoryId, key,
            startOfMonth(input.startMonth), installments, paidBefore, id
        )
    }
    return billId
}

fun deleteBill(db: Conn, id: String) {
    db.run("DELETE FROM bills WHERE id = ?", id)
}

/** A month the user marked paid, optionally with the transaction that paid it. */
data class BillMark(val billId: String, val month: Long, val transactionId: String?, val paidAt: Long)

fun listBillMarks(db: Conn): List<BillMark> = db.query("SELECT bill_id, month, transaction_id, paid_at FROM bill_payments") {
    BillMark(it.text(0), it.long(1), it.textOrNull(2), it.long(3))
}

fun markBillPaid(db: Conn, billId: String, month: Long, transactionId: String?, paidAt: Long = System.currentTimeMillis()) {
    db.run(
        "INSERT OR REPLACE INTO bill_payments (bill_id, month, transaction_id, paid_at) VALUES (?, ?, ?, ?)",
        billId,
        startOfMonth(month),
        transactionId,
        paidAt
    )
}

fun unmarkBillPaid(db: Conn, billId: String, month: Long) {
    db.run("DELETE FROM bill_payments WHERE bill_id = ? AND month = ?", billId, startOfMonth(month))
}

/** A money-out transaction that could pay a bill. */
data class BillTx(val id: String, val amountMinor: Long, val currency: Currency, val merchant: String?, val occurredAt: Long)

/** Accepted, not ignored money-out transactions from [from] on. */
fun listBillTxs(db: Conn, from: Long): List<BillTx> = db.query(
    """SELECT id, amount_minor, currency, merchant, occurred_at FROM transactions
       WHERE status = 'accepted' AND excluded = 0 AND direction = ? AND occurred_at >= ? ORDER BY occurred_at""",
    Direction.DEBIT.db,
    from
) { BillTx(it.text(0), it.long(1), Currency.from(it.text(2)) ?: Currency.AED, it.textOrNull(3), it.long(4)) }

/** The due date in [month]: the bill's day, or the month's last day when the month is shorter. */
fun dueDate(month: Long, dueDay: Int): Long {
    val c = Calendar.getInstance().apply { timeInMillis = month }
    val last = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    return localTime(c.get(Calendar.YEAR), c.get(Calendar.MONTH), minOf(dueDay, last))
}

/** Months between two month starts. */
fun monthsBetween(from: Long, to: Long): Int {
    val a = Calendar.getInstance().apply { timeInMillis = from }
    val b = Calendar.getInstance().apply { timeInMillis = to }
    return (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + b.get(Calendar.MONTH) - a.get(Calendar.MONTH)
}

/** EMI: the month of the last instalment. Null for a bill. */
fun lastMonth(bill: Bill): Long? = bill.installments?.let { addMonths(bill.startMonth, (it - bill.paidBefore - 1).coerceAtLeast(0)) }

/** Whether a transaction's merchant is the bill's (same key, or the key as whole words in it). */
fun matchesBill(bill: Bill, merchant: String?): Boolean {
    val key = bill.merchantKey ?: return false
    if (merchant.isNullOrBlank()) return false
    val k = merchantKey(merchant)
    return k == key || (key.length >= 3 && " $k ".contains(" $key "))
}

enum class MonthState { PAID, DUE, OVERDUE }

data class BillMonth(
    val month: Long,
    val dueAt: Long,
    val state: MonthState,
    /** The paying transaction, if known. */
    val transactionId: String?,
    val paidMinor: Long?,
    val paidAt: Long?,
    /** True when found from a transaction, false when marked by hand. */
    val auto: Boolean
)

/**
 * The status of every tracked month of every bill, up to [now]'s month (or the EMI's end).
 * A transaction pays at most one bill-month: among bills with the same merchant, the one whose
 * amount is closest gets it.
 */
fun billMonths(bills: List<Bill>, marks: List<BillMark>, txs: List<BillTx>, now: Long): Map<String, List<BillMonth>> {
    val today = startOfDay(now)
    val thisMonth = startOfMonth(now)
    val marksBy = marks.associateBy { it.billId to it.month }
    val txById = txs.associateBy { it.id }
    val used = HashSet<String>()
    marks.mapNotNullTo(used) { it.transactionId }
    val out = LinkedHashMap<String, MutableList<BillMonth>>()
    val months = bills.flatMap { b ->
        val end = lastMonth(b)?.let { minOf(it, thisMonth) } ?: thisMonth
        val n = monthsBetween(b.startMonth, end)
        (0..n).map { b to addMonths(b.startMonth, it) }
    }.sortedBy { it.second }
    for ((b, m) in months) {
        val due = dueDate(m, b.dueDay)
        val mark = marksBy[b.id to m]
        val next = addMonths(m, 1)
        val month = if (mark != null) {
            val tx = mark.transactionId?.let { txById[it] }
            BillMonth(m, due, MonthState.PAID, mark.transactionId, tx?.amountMinor ?: b.amountMinor, tx?.occurredAt ?: mark.paidAt, false)
        } else {
            val tx = txs.filter { it.id !in used && it.currency == b.currency && it.occurredAt >= m && it.occurredAt < next && matchesBill(b, it.merchant) }
                .minByOrNull { abs(it.amountMinor - b.amountMinor) }
            if (tx != null) {
                used.add(tx.id)
                BillMonth(m, due, MonthState.PAID, tx.id, tx.amountMinor, tx.occurredAt, true)
            } else {
                BillMonth(m, due, if (due < today) MonthState.OVERDUE else MonthState.DUE, null, null, null, false)
            }
        }
        out.getOrPut(b.id) { ArrayList() }.add(month)
    }
    return bills.associate { b -> b.id to (out[b.id] ?: emptyList<BillMonth>()).sortedByDescending { it.month } }
}

data class BillProgress(
    /** Instalments / months paid (EMI: including those paid before tracking). */
    val paidCount: Int,
    val paidMinor: Long,
    /** EMI only. */
    val totalCount: Int?,
    val remainingCount: Int?,
    val remainingMinor: Long?,
    val lastMonth: Long?
) {
    val done: Boolean get() = remainingCount == 0
}

/** Paid so far and what is left. EMI amounts use the instalment amount; bills use what was actually paid. */
fun billProgress(bill: Bill, months: List<BillMonth>): BillProgress {
    val paid = months.filter { it.state == MonthState.PAID }
    return if (bill.kind == BillKind.EMI && bill.installments != null) {
        val count = (bill.paidBefore + paid.size).coerceAtMost(bill.installments)
        val left = (bill.installments - count).coerceAtLeast(0)
        BillProgress(count, count * bill.amountMinor, bill.installments, left, left * bill.amountMinor, lastMonth(bill))
    } else {
        BillProgress(paid.size, paid.sumOf { it.paidMinor ?: bill.amountMinor }, null, null, null, null)
    }
}

/** Money-out transactions of [month] that could be the payment, closest amount first. */
fun paymentCandidates(bill: Bill, month: Long, txs: List<BillTx>, taken: Set<String>): List<BillTx> {
    val next = addMonths(month, 1)
    return txs.filter { it.currency == bill.currency && it.id !in taken && it.occurredAt >= month && it.occurredAt < next }
        .sortedBy { abs(it.amountMinor - bill.amountMinor) }
}

/** Everything the bill screens show, read in one go. */
class BillsSnapshot(
    val bills: List<Bill>,
    val months: Map<String, List<BillMonth>>,
    val txs: List<BillTx>,
    val marks: List<BillMark>
) {
    fun progress(bill: Bill): BillProgress = billProgress(bill, months[bill.id].orEmpty())

    /** The running month's status, or null when the EMI is finished or hasn't started. */
    fun current(bill: Bill, now: Long): BillMonth? = months[bill.id]?.firstOrNull { it.month == startOfMonth(now) }

    /** Transactions already paying some bill-month. */
    val takenTxIds: Set<String> get() = months.values.flatten().mapNotNull { it.transactionId }.toSet()
}

fun loadBills(db: Conn, now: Long): BillsSnapshot {
    val bills = listBills(db)
    val from = bills.minOfOrNull { it.startMonth } ?: startOfMonth(now)
    val txs = listBillTxs(db, from)
    val marks = listBillMarks(db)
    return BillsSnapshot(bills, billMonths(bills, marks, txs, now), txs, marks)
}
