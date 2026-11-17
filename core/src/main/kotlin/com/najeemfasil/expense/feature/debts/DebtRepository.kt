package com.najeemfasil.expense.feature.debts

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.newId
import com.najeemfasil.expense.data.db.Conn

/** Which way the money went in one debt entry. */
enum class DebtKind(val db: String, val label: String) {
    /** I gave them money (lent, or paid them back): they owe me more / I owe them less. */
    GAVE("gave", "You gave"),

    /** I got money from them (borrowed, or they paid me back): I owe them more / they owe me less. */
    GOT("got", "You got");

    companion object {
        fun from(value: String?): DebtKind = entries.firstOrNull { it.db == value } ?: GAVE
    }
}

data class DebtPerson(val id: String, val name: String, val phone: String?, val contactKey: String?)

/** A person with what is outstanding, per currency. Positive: they owe me; negative: I owe them. */
data class PersonBalance(
    val person: DebtPerson,
    val balances: Map<Currency, Long>,
    val lastAt: Long?
)

data class DebtEntry(
    val id: String,
    val personId: String,
    val kind: DebtKind,
    val amountMinor: Long,
    val currency: Currency,
    val note: String?,
    val occurredAt: Long
) {
    /** What this entry adds to "they owe me". */
    val signed: Long get() = if (kind == DebtKind.GAVE) amountMinor else -amountMinor
}

data class DebtInput(
    val kind: DebtKind,
    val amountMinor: Long,
    val currency: Currency,
    val note: String?,
    val occurredAt: Long
)

/** Totals across everyone, per currency: what others owe me and what I owe others. */
data class DebtTotals(val toGet: Map<Currency, Long>, val toGive: Map<Currency, Long>)

private fun personOf(r: com.najeemfasil.expense.data.db.Row, offset: Int = 0) = DebtPerson(r.text(offset), r.text(offset + 1), r.textOrNull(offset + 2), r.textOrNull(offset + 3))

fun getPerson(db: Conn, id: String): DebtPerson? = db.first("SELECT id, name, phone, contact_key FROM debt_people WHERE id = ?", id) { personOf(it) }

/**
 * Everyone, with balances. People who owe me or whom I owe come first (latest activity first),
 * then settled people.
 */
fun listPeopleWithBalances(db: Conn): List<PersonBalance> {
    val people = db.query("SELECT id, name, phone, contact_key FROM debt_people ORDER BY name COLLATE NOCASE") { personOf(it) }
    val sums = HashMap<String, MutableMap<Currency, Long>>()
    db.query(
        """SELECT person_id, currency, SUM(CASE WHEN kind = 'gave' THEN amount_minor ELSE -amount_minor END)
           FROM debts GROUP BY person_id, currency"""
    ) { Triple(it.text(0), Currency.from(it.text(1)), it.long(2)) }.forEach { (pid, cur, sum) ->
        if (cur != null && sum != 0L) sums.getOrPut(pid) { LinkedHashMap() }[cur] = sum
    }
    val last = db.query("SELECT person_id, MAX(occurred_at) FROM debts GROUP BY person_id") { it.text(0) to it.long(1) }.toMap()
    return people
        .map { PersonBalance(it, sums[it.id].orEmpty().toSortedMap(compareBy { c -> c.ordinal }), last[it.id]) }
        .sortedWith(compareBy<PersonBalance> { it.balances.isEmpty() }.thenByDescending { it.lastAt ?: 0L })
}

fun totals(people: List<PersonBalance>): DebtTotals {
    val get = sortedMapOf<Currency, Long>(compareBy { it.ordinal })
    val give = sortedMapOf<Currency, Long>(compareBy { it.ordinal })
    for (p in people) {
        for ((cur, v) in p.balances) {
            if (v > 0) get[cur] = (get[cur] ?: 0) + v else give[cur] = (give[cur] ?: 0) - v
        }
    }
    return DebtTotals(get, give)
}

fun normalizePhone(phone: String?): String? = phone?.filter { it.isDigit() || it == '+' }?.ifEmpty { null }

/**
 * Adds a person, or returns the existing one with the same contact or phone number so picking
 * the same contact twice doesn't create a duplicate. Returns the person's id.
 */
fun addPerson(db: Conn, name: String, phone: String?, contactKey: String?): String {
    val cleanName = name.jsTrim().ifEmpty { phone?.jsTrim().orEmpty() }
    val cleanPhone = phone?.jsTrim()?.ifEmpty { null }
    if (contactKey != null) {
        db.first("SELECT id FROM debt_people WHERE contact_key = ?", contactKey) { it.text(0) }?.let { return it }
    }
    val digits = normalizePhone(cleanPhone)
    if (digits != null) {
        val match = db.query("SELECT id, phone FROM debt_people WHERE phone IS NOT NULL") { it.text(0) to it.text(1) }
            .firstOrNull { normalizePhone(it.second) == digits }
        if (match != null) return match.first
    }
    val id = newId()
    db.run(
        "INSERT INTO debt_people (id, name, phone, contact_key, created_at) VALUES (?, ?, ?, ?, ?)",
        id,
        cleanName,
        cleanPhone,
        contactKey,
        System.currentTimeMillis()
    )
    return id
}

fun updatePerson(db: Conn, id: String, name: String, phone: String?) {
    val cleanName = name.jsTrim()
    if (cleanName.isEmpty()) return
    db.run("UPDATE debt_people SET name = ?, phone = ? WHERE id = ?", cleanName, phone?.jsTrim()?.ifEmpty { null }, id)
}

/** Removes the person and all their entries. */
fun deletePerson(db: Conn, id: String) {
    db.run("DELETE FROM debt_people WHERE id = ?", id)
}

private const val ENTRY_COLS = "id, person_id, kind, amount_minor, currency, note, occurred_at"

private fun entryOf(r: com.najeemfasil.expense.data.db.Row) = DebtEntry(
    r.text(0),
    r.text(1),
    DebtKind.from(r.text(2)),
    r.long(3),
    Currency.from(r.text(4)) ?: Currency.AED,
    r.textOrNull(5),
    r.long(6)
)

/** Newest first. */
fun listEntries(db: Conn, personId: String): List<DebtEntry> = db.query("SELECT $ENTRY_COLS FROM debts WHERE person_id = ? ORDER BY occurred_at DESC, created_at DESC", personId) { entryOf(it) }

fun getEntry(db: Conn, id: String): DebtEntry? = db.first("SELECT $ENTRY_COLS FROM debts WHERE id = ?", id) { entryOf(it) }

/** Creates (when [id] is null) or updates an entry. Returns its id. */
fun saveEntry(db: Conn, personId: String, id: String?, input: DebtInput): String {
    val now = System.currentTimeMillis()
    val note = input.note?.jsTrim()?.ifEmpty { null }
    return if (id == null) {
        val newId = newId()
        db.run(
            """INSERT INTO debts (id, person_id, kind, amount_minor, currency, note, occurred_at, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            newId,
            personId,
            input.kind.db,
            input.amountMinor,
            input.currency.name,
            note,
            input.occurredAt,
            now,
            now
        )
        newId
    } else {
        db.run(
            """UPDATE debts SET kind = ?, amount_minor = ?, currency = ?, note = ?, occurred_at = ?, updated_at = ?
               WHERE id = ?""",
            input.kind.db,
            input.amountMinor,
            input.currency.name,
            note,
            input.occurredAt,
            now,
            id
        )
        id
    }
}

fun deleteEntry(db: Conn, id: String) {
    db.run("DELETE FROM debts WHERE id = ?", id)
}

/** Balance per currency from a list of entries (zero balances left out). */
fun balancesOf(entries: List<DebtEntry>): Map<Currency, Long> = entries.groupBy { it.currency }
    .mapValues { (_, list) -> list.sumOf { it.signed } }
    .filterValues { it != 0L }
    .toSortedMap(compareBy { it.ordinal })
