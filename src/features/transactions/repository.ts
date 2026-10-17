import type { SQLiteDatabase } from 'expo-sqlite';

import { notifyDbChanged } from '@/db/live';
import type { SummaryInput } from '@/features/dashboard/summary';
import { learnRule } from '@/features/categories/repository';
import type { TransactionDraft } from '@/features/sms/ingest';
import { newId } from '@/lib/id';
import type { Currency, Direction } from '@/lib/money';

import type { DedupeCandidate } from './dedupe';

export type TransactionStatus = 'pending' | 'accepted' | 'rejected';

export type Transaction = {
  id: string;
  status: TransactionStatus;
  source: 'sms' | 'manual';
  amountMinor: number;
  currency: Currency;
  direction: Direction;
  merchant: string | null;
  note: string | null;
  occurredAt: number;
  accountId: string | null;
  /** Last digits named in the SMS; set even when no account matches yet. */
  accountHint: string | null;
  categoryId: string | null;
  duplicateOfId: string | null;
  smsSender: string | null;
  smsBody: string | null;
};

const COLUMNS = `id, status, source, amount_minor AS amountMinor, currency, direction, merchant, note,
  occurred_at AS occurredAt, account_id AS accountId, account_hint AS accountHint, category_id AS categoryId,
  duplicate_of_id AS duplicateOfId, sms_sender AS smsSender, sms_body AS smsBody`;

export async function getTransaction(db: SQLiteDatabase, id: string): Promise<Transaction | null> {
  return db.getFirstAsync<Transaction>(`SELECT ${COLUMNS} FROM transactions WHERE id = ?`, id);
}

export async function listPending(db: SQLiteDatabase): Promise<Transaction[]> {
  return db.getAllAsync<Transaction>(
    `SELECT ${COLUMNS} FROM transactions WHERE status = 'pending' ORDER BY occurred_at DESC`,
  );
}

export async function countPending(db: SQLiteDatabase): Promise<number> {
  const row = await db.getFirstAsync<{ n: number }>(`SELECT COUNT(*) AS n FROM transactions WHERE status = 'pending'`);
  return row?.n ?? 0;
}

/** Accepted transactions in [from, to). */
export async function listAccepted(db: SQLiteDatabase, from: number, to: number, limit = 1000): Promise<Transaction[]> {
  return db.getAllAsync<Transaction>(
    `SELECT ${COLUMNS} FROM transactions
     WHERE status = 'accepted' AND occurred_at >= ? AND occurred_at < ?
     ORDER BY occurred_at DESC LIMIT ?`,
    from,
    to,
    limit,
  );
}

/** Amount fields of every accepted transaction in [from, to), for totals (no row limit). */
export async function listSummaryInputs(db: SQLiteDatabase, from: number, to: number): Promise<SummaryInput[]> {
  return db.getAllAsync<SummaryInput>(
    `SELECT amount_minor AS amountMinor, currency, direction, category_id AS categoryId FROM transactions
     WHERE status = 'accepted' AND occurred_at >= ? AND occurred_at < ?`,
    from,
    to,
  );
}

/** Non-rejected transactions between `from` and `to` widened by a day, for duplicate detection. */
export async function listForDedupe(db: SQLiteDatabase, from: number, to: number): Promise<DedupeCandidate[]> {
  const day = 24 * 60 * 60 * 1000;
  return db.getAllAsync<DedupeCandidate>(
    `SELECT id, amount_minor AS amountMinor, currency, direction, occurred_at AS occurredAt, account_id AS accountId
     FROM transactions WHERE status != 'rejected' AND occurred_at BETWEEN ? AND ?`,
    from - day,
    to + day,
  );
}

export type DraftRow = TransactionDraft & { id: string; status: 'pending' | 'accepted' };

/**
 * Inserts SMS drafts with one prepared statement. Rows whose SMS was already imported
 * (including rejected ones) are skipped. Returns the ids actually inserted. Run inside a
 * transaction; caller notifies.
 */
export async function insertDrafts(db: SQLiteDatabase, drafts: DraftRow[]): Promise<Set<string>> {
  const inserted = new Set<string>();
  if (drafts.length === 0) return inserted;
  const now = Date.now();
  const stmt = await db.prepareAsync(
    `INSERT OR IGNORE INTO transactions
      (id, status, source, amount_minor, currency, direction, merchant, occurred_at, account_id, account_hint,
       category_id, duplicate_of_id, sms_hash, sms_sender, sms_body, parser_id, created_at, updated_at)
     VALUES (?, ?, 'sms', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  );
  try {
    for (const d of drafts) {
      const res = await stmt.executeAsync(
        d.id, d.status, d.amountMinor, d.currency, d.direction, d.merchant, d.occurredAt, d.accountId,
        d.accountHint, d.categoryId, d.duplicateOfId, d.smsHash, d.smsSender, d.smsBody, d.parserId, now, now,
      );
      if (res.changes > 0) inserted.add(d.id);
    }
  } finally {
    await stmt.finalizeAsync();
  }
  return inserted;
}

export type TransactionInput = {
  amountMinor: number;
  currency: Currency;
  direction: Direction;
  merchant: string | null;
  note: string | null;
  occurredAt: number;
  accountId: string | null;
  categoryId: string | null;
};

/**
 * Creates a manual transaction (id null) or updates one. Saving always accepts it,
 * and teaches the categorizer the merchant → category choice.
 */
export async function saveTransaction(db: SQLiteDatabase, id: string | null, input: TransactionInput): Promise<string> {
  const now = Date.now();
  const txId = id ?? newId();
  await db.withTransactionAsync(async () => {
    if (id) {
      await db.runAsync(
        `UPDATE transactions SET status = 'accepted', amount_minor = ?, currency = ?, direction = ?, merchant = ?,
           note = ?, occurred_at = ?, account_id = ?, category_id = ?, updated_at = ? WHERE id = ?`,
        input.amountMinor, input.currency, input.direction, input.merchant, input.note,
        input.occurredAt, input.accountId, input.categoryId, now, id,
      );
    } else {
      await db.runAsync(
        `INSERT INTO transactions (id, status, source, amount_minor, currency, direction, merchant, note,
           occurred_at, account_id, category_id, created_at, updated_at)
         VALUES (?, 'accepted', 'manual', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        txId, input.amountMinor, input.currency, input.direction, input.merchant, input.note,
        input.occurredAt, input.accountId, input.categoryId, now, now,
      );
    }
    await learnRule(db, input.merchant, input.categoryId);
  });
  notifyDbChanged();
  return txId;
}

const CHUNK = 500;

/** Learns merchant → category from the given transactions (only rows that have both). */
async function learnFrom(db: SQLiteDatabase, where: string, params: (string | number)[]): Promise<void> {
  const pairs = await db.getAllAsync<{ merchant: string; categoryId: string }>(
    `SELECT DISTINCT merchant, category_id AS categoryId FROM transactions
     WHERE ${where} AND merchant IS NOT NULL AND category_id IS NOT NULL`,
    ...params,
  );
  for (const p of pairs) await learnRule(db, p.merchant, p.categoryId);
}

export async function setStatus(db: SQLiteDatabase, ids: string[], status: TransactionStatus): Promise<void> {
  if (ids.length === 0) return;
  await db.withTransactionAsync(async () => {
    for (let i = 0; i < ids.length; i += CHUNK) {
      const chunk = ids.slice(i, i + CHUNK);
      const marks = chunk.map(() => '?').join(',');
      await db.runAsync(
        `UPDATE transactions SET status = ?, updated_at = ? WHERE id IN (${marks})`,
        status,
        Date.now(),
        ...chunk,
      );
      if (status === 'accepted') await learnFrom(db, `id IN (${marks})`, chunk);
    }
  });
  notifyDbChanged();
}

/** Accepts the whole Review queue in one statement. Returns how many were approved. */
export async function approveAllPending(db: SQLiteDatabase, includeDuplicates: boolean): Promise<number> {
  const where = includeDuplicates ? `status = 'pending'` : `status = 'pending' AND duplicate_of_id IS NULL`;
  let changed = 0;
  await db.withTransactionAsync(async () => {
    await learnFrom(db, where, []);
    const res = await db.runAsync(`UPDATE transactions SET status = 'accepted', updated_at = ? WHERE ${where}`, Date.now());
    changed = res.changes;
  });
  notifyDbChanged();
  return changed;
}

/** Manual entries are deleted; SMS entries become rejected so the SMS isn't re-imported. */
export async function removeTransaction(db: SQLiteDatabase, tx: Transaction): Promise<void> {
  if (tx.source === 'sms') {
    await setStatus(db, [tx.id], 'rejected');
  } else {
    await db.runAsync('DELETE FROM transactions WHERE id = ?', tx.id);
    notifyDbChanged();
  }
}
