package com.najeemfasil.expense.feature.sms

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction
import com.najeemfasil.expense.core.jsTrim
import com.najeemfasil.expense.core.newId
import com.najeemfasil.expense.data.db.Conn
import com.najeemfasil.expense.feature.accounts.listAccounts
import com.najeemfasil.expense.feature.categories.listRules
import com.najeemfasil.expense.feature.transactions.DedupeCandidate
import com.najeemfasil.expense.feature.transactions.DedupeIndex
import com.najeemfasil.expense.feature.transactions.TransactionStatus
import com.najeemfasil.expense.feature.transactions.listForDedupe

data class StoredParser(val parser: CustomParser, val sample: String)

/** Field names are stored as a JSON array of strings, as the Expo app wrote them. */
internal fun fieldsToJson(fields: List<FieldName>): String = fields.joinToString(",", "[", "]") { "\"${it.db}\"" }

internal fun fieldsFromJson(json: String): List<FieldName> = Regex("\"([a-z]+)\"").findAll(json).mapNotNull { FieldName.from(it.groupValues[1]) }.toList()

fun listCustomParsers(db: Conn): List<StoredParser> = db.query(
    "SELECT id, name, sender_contains, pattern, fields, currency, direction, sample FROM custom_parsers ORDER BY created_at DESC"
) {
    StoredParser(
        CustomParser(
            id = it.text(0),
            name = it.text(1),
            senderContains = it.textOrNull(2),
            pattern = it.text(3),
            fields = fieldsFromJson(it.text(4)),
            currency = Currency.from(it.text(5)) ?: Currency.AED,
            direction = Direction.from(it.text(6)) ?: Direction.DEBIT
        ),
        it.text(7)
    )
}

/** Saves a format; the parser's id is ignored and a new one is made. */
fun createCustomParser(db: Conn, parser: CustomParser, sample: String) {
    db.run(
        """INSERT INTO custom_parsers (id, name, sender_contains, pattern, fields, currency, direction, sample, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        newId(),
        parser.name.jsTrim(),
        parser.senderContains?.jsTrim()?.ifEmpty { null },
        parser.pattern,
        fieldsToJson(parser.fields),
        parser.currency.name,
        parser.direction.db,
        sample,
        System.currentTimeMillis()
    )
}

fun deleteCustomParser(db: Conn, id: String) {
    db.run("DELETE FROM custom_parsers WHERE id = ?", id)
}

data class ImportSummary(
    val read: Int = 0,
    val added: Int = 0,
    /** Of `added`, how many went straight to totals (SMS setup) instead of Review. */
    val accepted: Int = 0,
    val duplicates: Int = 0,
    /** Not a configured kind of message. */
    val ignored: Int = 0,
    /** From a configured sender with an amount, but no kind matched. */
    val unread: Int = 0
) {
    operator fun plus(o: ImportSummary) = ImportSummary(
        read + o.read,
        added + o.added,
        accepted + o.accepted,
        duplicates + o.duplicates,
        ignored + o.ignored,
        unread + o.unread
    )
}

fun loadIngestContext(db: Conn, from: Long, to: Long): IngestContext = IngestContext(
    smsRules = listSmsRules(db),
    customParsers = listCustomParsers(db).map { it.parser },
    accounts = listAccounts(db).map { AccountRef(it.id, it.currency, it.smsHint) },
    dedupe = DedupeIndex(listForDedupe(db, from, to)),
    rules = listRules(db)
)

const val IMPORT_CHUNK = 200

private class Row(val id: String, val status: TransactionStatus, val draft: TransactionDraft)

/** Decides a chunk in memory, then writes it in one transaction with prepared statements. */
fun importChunk(db: Conn, messages: List<RawSms>, ctx: IngestContext, autoAccept: Boolean): Pair<ImportSummary, List<IngestResult>> {
    val outcomes = ArrayList<IngestResult>(messages.size)
    val rows = ArrayList<Row>()
    val unread = ArrayList<RawSms>()
    val resolved = ArrayList<String>()

    for (sms in messages) {
        val result = processSms(sms, ctx)
        outcomes.add(result)
        when (result) {
            is IngestResult.Unparsed -> unread.add(sms)
            is IngestResult.Draft -> {
                val d = result.draft
                val id = newId()
                val status = if (autoAccept && d.duplicateOfId == null) TransactionStatus.ACCEPTED else TransactionStatus.PENDING
                rows.add(Row(id, status, d))
                resolved.add(d.smsHash)
                ctx.dedupe.add(DedupeCandidate(id, d.amountMinor, d.currency, d.direction, d.occurredAt, d.accountId))
            }
            else -> Unit
        }
    }

    val inserted = HashSet<String>()
    db.transaction {
        if (rows.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.prepare(
                """INSERT OR IGNORE INTO transactions
                     (id, status, source, amount_minor, currency, direction, merchant, occurred_at, account_id, account_hint,
                      category_id, duplicate_of_id, sms_hash, sms_sender, sms_body, parser_id, created_at, updated_at)
                   VALUES (?, ?, 'sms', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { stmt ->
                for (r in rows) {
                    val d = r.draft
                    val changed = stmt.run(
                        r.id, r.status.db, d.amountMinor, d.currency.name, d.direction.db, d.merchant, d.occurredAt,
                        d.accountId, d.accountHint, d.categoryId, d.duplicateOfId, d.smsHash, d.smsSender, d.smsBody,
                        d.parserId, now, now
                    )
                    if (changed > 0) inserted.add(r.id)
                }
            }
        }
        if (unread.isNotEmpty()) {
            db.prepare("INSERT OR IGNORE INTO unread_sms (sms_hash, sender, body, received_at) VALUES (?, ?, ?, ?)").use { stmt ->
                for (sms in unread) stmt.run(smsHash(sms), sms.sender, sms.body, sms.receivedAt)
            }
        }
        if (resolved.isNotEmpty()) {
            db.prepare("DELETE FROM unread_sms WHERE sms_hash = ?").use { stmt -> for (h in resolved) stmt.run(h) }
        }
    }

    var added = 0
    var accepted = 0
    var duplicates = 0
    var ignored = 0
    var unreadCount = 0
    var ri = 0
    for (i in outcomes.indices) {
        when (outcomes[i]) {
            is IngestResult.Draft -> {
                val row = rows[ri++]
                if (row.id !in inserted) {
                    outcomes[i] = IngestResult.AlreadyImported
                    ignored++
                } else {
                    added++
                    if (row.draft.duplicateOfId != null) {
                        duplicates++
                    } else if (row.status == TransactionStatus.ACCEPTED) {
                        accepted++
                    }
                }
            }
            is IngestResult.Unparsed -> unreadCount++
            else -> ignored++
        }
    }
    return ImportSummary(messages.size, added, accepted, duplicates, ignored, unreadCount) to outcomes
}

/** Full pipeline for one SMS: only configured kinds become transactions (pending in Review). */
fun importSms(db: Conn, sms: RawSms): IngestResult {
    val ctx = loadIngestContext(db, sms.receivedAt, sms.receivedAt)
    return importChunk(db, listOf(sms), ctx, autoAccept = false).second.single()
}

/**
 * Imports many SMS (oldest first) in chunks of [IMPORT_CHUNK]. With [autoAccept], drafts count
 * in totals right away; possible duplicates still go to Review. [onProgress] gets the running
 * total after each chunk.
 */
fun importBatch(
    db: Conn,
    messages: List<RawSms>,
    autoAccept: Boolean = false,
    onProgress: ((ImportSummary) -> Unit)? = null
): ImportSummary {
    var total = ImportSummary()
    if (messages.isEmpty()) return total
    val ctx = loadIngestContext(db, messages.minOf { it.receivedAt }, messages.maxOf { it.receivedAt })
    for (chunk in messages.chunked(IMPORT_CHUNK)) {
        total += importChunk(db, chunk, ctx, autoAccept).first
        onProgress?.invoke(total)
    }
    return total
}

/** Removes everything imported from SMS (manual transactions stay) before a fresh setup import. */
fun clearSmsTransactions(db: Conn): Int = db.transaction {
    val removed = db.run("DELETE FROM transactions WHERE source = 'sms'")
    db.run("DELETE FROM unread_sms")
    removed
}

fun countSmsTransactions(db: Conn): Int = db.first("SELECT COUNT(*) FROM transactions WHERE source = 'sms'") { it.int(0) } ?: 0

data class UnreadSms(val smsHash: String, val sender: String, val body: String, val receivedAt: Long)

/** Messages with an amount that no parser could read, newest first. */
fun listUnread(db: Conn): List<UnreadSms> = db.query("SELECT sms_hash, sender, body, received_at FROM unread_sms ORDER BY received_at DESC") {
    UnreadSms(it.text(0), it.text(1), it.text(2), it.long(3))
}

fun dismissUnread(db: Conn, smsHash: String) {
    db.run("DELETE FROM unread_sms WHERE sms_hash = ?", smsHash)
}

/** After a new parser is saved, try the unread messages again. */
fun recheckUnread(db: Conn): ImportSummary = importBatch(db, listUnread(db).asReversed().map { RawSms(it.sender, it.body, it.receivedAt) })
