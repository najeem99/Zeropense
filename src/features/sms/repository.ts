import type { SQLiteDatabase } from 'expo-sqlite';

import { notifyDbChanged } from '@/db/live';
import { listAccounts } from '@/features/accounts/repository';
import { listRules } from '@/features/categories/repository';
import { insertDraft, listForDedupe } from '@/features/transactions/repository';
import { newId } from '@/lib/id';
import type { Currency, Direction } from '@/lib/money';

import type { CustomParser, FieldName } from './custom-parser';
import { processSms, smsHash, type IngestContext, type IngestResult } from './ingest';
import type { RawSms } from './types';

type ParserRow = {
  id: string;
  name: string;
  sender_contains: string | null;
  pattern: string;
  fields: string;
  currency: Currency;
  direction: Direction;
  sample: string;
};

export type StoredParser = CustomParser & { sample: string };

export async function listCustomParsers(db: SQLiteDatabase): Promise<StoredParser[]> {
  const rows = await db.getAllAsync<ParserRow>('SELECT * FROM custom_parsers ORDER BY created_at DESC');
  return rows.map((r) => ({
    id: r.id,
    name: r.name,
    senderContains: r.sender_contains,
    pattern: r.pattern,
    fields: JSON.parse(r.fields) as FieldName[],
    currency: r.currency,
    direction: r.direction,
    sample: r.sample,
  }));
}

export async function createCustomParser(db: SQLiteDatabase, p: Omit<StoredParser, 'id'>): Promise<void> {
  await db.runAsync(
    `INSERT INTO custom_parsers (id, name, sender_contains, pattern, fields, currency, direction, sample, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    newId(),
    p.name.trim(),
    p.senderContains?.trim() || null,
    p.pattern,
    JSON.stringify(p.fields),
    p.currency,
    p.direction,
    p.sample,
    Date.now(),
  );
  notifyDbChanged();
}

export async function deleteCustomParser(db: SQLiteDatabase, id: string): Promise<void> {
  await db.runAsync('DELETE FROM custom_parsers WHERE id = ?', id);
  notifyDbChanged();
}

export type ImportOutcome = IngestResult | { kind: 'already-imported' };

export type ImportSummary = {
  read: number;
  added: number;
  /** Of `added`, how many went straight to totals (history import) instead of Review. */
  accepted: number;
  duplicates: number;
  skipped: number;
  unread: number;
};

type Ctx = IngestContext;

async function loadContext(db: SQLiteDatabase, from: number, to: number): Promise<Ctx> {
  const [customParsers, accounts, rules, recent] = await Promise.all([
    listCustomParsers(db),
    listAccounts(db),
    listRules(db),
    listForDedupe(db, from, to),
  ]);
  return { customParsers, accounts, rules, recent };
}

export type ImportOptions = {
  /** Store drafts as accepted so they count in totals right away. Possible duplicates still go to Review. */
  autoAccept?: boolean;
};

/** Runs one SMS through the pipeline and stores the result. Adds new drafts to ctx.recent. */
async function processAndStore(
  db: SQLiteDatabase,
  sms: RawSms,
  ctx: Ctx,
  opts: ImportOptions = {},
): Promise<ImportOutcome> {
  const result = processSms(sms, ctx);
  const hash = smsHash(sms);

  if (result.kind === 'unparsed') {
    if (result.looksFinancial) {
      await db.runAsync(
        'INSERT OR IGNORE INTO unread_sms (sms_hash, sender, body, received_at) VALUES (?, ?, ?, ?)',
        hash,
        sms.sender,
        sms.body,
        sms.receivedAt,
      );
    }
    return result;
  }
  await db.runAsync('DELETE FROM unread_sms WHERE sms_hash = ?', hash);
  if (result.kind !== 'draft') return result;

  const status = opts.autoAccept && !result.draft.duplicateOfId ? 'accepted' : 'pending';
  const id = await insertDraft(db, result.draft, status);
  if (!id) return { kind: 'already-imported' };
  const d = result.draft;
  ctx.recent.push({
    id,
    amountMinor: d.amountMinor,
    currency: d.currency,
    direction: d.direction,
    occurredAt: d.occurredAt,
    accountId: d.accountId,
  });
  return result;
}

/** Full pipeline for one SMS: filter, parse, match card, categorise, dedupe, queue for review. */
export async function importSms(db: SQLiteDatabase, sms: RawSms): Promise<ImportOutcome> {
  const ctx = await loadContext(db, sms.receivedAt, sms.receivedAt);
  const outcome = await processAndStore(db, sms, ctx);
  notifyDbChanged();
  return outcome;
}

/** Imports many SMS (oldest first) in one transaction, sharing parsers, rules and dedupe state. */
export async function importBatch(
  db: SQLiteDatabase,
  messages: RawSms[],
  opts: ImportOptions = {},
): Promise<ImportSummary> {
  const summary: ImportSummary = { read: messages.length, added: 0, accepted: 0, duplicates: 0, skipped: 0, unread: 0 };
  if (messages.length === 0) return summary;

  const times = messages.map((m) => m.receivedAt);
  const ctx = await loadContext(db, Math.min(...times), Math.max(...times));
  await db.withTransactionAsync(async () => {
    for (const sms of messages) {
      const outcome = await processAndStore(db, sms, ctx, opts);
      if (outcome.kind === 'draft') {
        summary.added++;
        if (outcome.draft.duplicateOfId) summary.duplicates++;
        else if (opts.autoAccept) summary.accepted++;
      } else if (outcome.kind === 'unparsed') {
        if (outcome.looksFinancial) summary.unread++;
      } else {
        summary.skipped++;
      }
    }
  });
  notifyDbChanged();
  return summary;
}

export type UnreadSms = { smsHash: string; sender: string; body: string; receivedAt: number };

/** Messages with an amount that no parser could read, newest first. */
export async function listUnread(db: SQLiteDatabase): Promise<UnreadSms[]> {
  return db.getAllAsync<UnreadSms>(
    `SELECT sms_hash AS smsHash, sender, body, received_at AS receivedAt FROM unread_sms ORDER BY received_at DESC`,
  );
}

export async function dismissUnread(db: SQLiteDatabase, smsHash: string): Promise<void> {
  await db.runAsync('DELETE FROM unread_sms WHERE sms_hash = ?', smsHash);
  notifyDbChanged();
}

/** After a new parser is saved, try the unread messages again. */
export async function recheckUnread(db: SQLiteDatabase): Promise<ImportSummary> {
  const unread = await listUnread(db);
  return importBatch(
    db,
    unread.reverse().map((u) => ({ sender: u.sender, body: u.body, receivedAt: u.receivedAt })),
  );
}
