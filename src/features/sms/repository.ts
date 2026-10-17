import type { SQLiteDatabase } from 'expo-sqlite';

import { notifyDbChanged } from '@/db/live';
import { listAccounts } from '@/features/accounts/repository';
import { listRules } from '@/features/categories/repository';
import { DedupeIndex } from '@/features/transactions/dedupe';
import { insertDrafts, listForDedupe, type DraftRow } from '@/features/transactions/repository';
import { newId } from '@/lib/id';
import type { Currency, Direction } from '@/lib/money';

import type { CustomParser, FieldName } from './custom-parser';
import { processSms, smsHash, type IngestContext, type IngestResult } from './ingest';
import { listSmsRules } from './rules-repository';
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
  /** Of `added`, how many went straight to totals (SMS setup) instead of Review. */
  accepted: number;
  duplicates: number;
  /** Not a configured kind of message. */
  ignored: number;
  /** From a configured sender with an amount, but no kind matched. */
  unread: number;
};

export const emptySummary = (): ImportSummary => ({
  read: 0,
  added: 0,
  accepted: 0,
  duplicates: 0,
  ignored: 0,
  unread: 0,
});

export function addSummary(total: ImportSummary, s: ImportSummary): void {
  total.read += s.read;
  total.added += s.added;
  total.accepted += s.accepted;
  total.duplicates += s.duplicates;
  total.ignored += s.ignored;
  total.unread += s.unread;
}

async function loadContext(db: SQLiteDatabase, from: number, to: number): Promise<IngestContext> {
  const [smsRules, customParsers, accounts, rules, recent] = await Promise.all([
    listSmsRules(db),
    listCustomParsers(db),
    listAccounts(db),
    listRules(db),
    listForDedupe(db, from, to),
  ]);
  return { smsRules, customParsers, accounts, rules, dedupe: new DedupeIndex(recent) };
}

export type ImportOptions = {
  /** Store drafts as accepted so they count in totals right away. Possible duplicates still go to Review. */
  autoAccept?: boolean;
  onProgress?: (s: ImportSummary) => void;
};

const CHUNK = 200;
/** Lets the UI render between chunks so long imports don't freeze the app. */
const yieldToUi = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/** Decides a chunk in memory, then writes it in one transaction with prepared statements. */
async function importChunk(
  db: SQLiteDatabase,
  messages: RawSms[],
  ctx: IngestContext,
  opts: ImportOptions,
): Promise<{ summary: ImportSummary; outcomes: ImportOutcome[] }> {
  const summary = emptySummary();
  summary.read = messages.length;
  const outcomes: ImportOutcome[] = [];
  const drafts: DraftRow[] = [];
  const unread: RawSms[] = [];
  const resolved: string[] = [];

  for (const sms of messages) {
    const result = processSms(sms, ctx);
    outcomes.push(result);
    if (result.kind === 'unparsed') {
      unread.push(sms);
    } else if (result.kind === 'draft') {
      const d = result.draft;
      const id = newId();
      drafts.push({ ...d, id, status: opts.autoAccept && !d.duplicateOfId ? 'accepted' : 'pending' });
      resolved.push(d.smsHash);
      ctx.dedupe.add({ id, amountMinor: d.amountMinor, currency: d.currency, direction: d.direction, occurredAt: d.occurredAt, accountId: d.accountId });
    }
  }

  let inserted = new Set<string>();
  await db.withTransactionAsync(async () => {
    inserted = await insertDrafts(db, drafts);
    for (const sms of unread) {
      await db.runAsync(
        'INSERT OR IGNORE INTO unread_sms (sms_hash, sender, body, received_at) VALUES (?, ?, ?, ?)',
        smsHash(sms),
        sms.sender,
        sms.body,
        sms.receivedAt,
      );
    }
    for (const hash of resolved) await db.runAsync('DELETE FROM unread_sms WHERE sms_hash = ?', hash);
  });

  let di = 0;
  for (let i = 0; i < outcomes.length; i++) {
    const o = outcomes[i];
    if (o.kind === 'draft') {
      const row = drafts[di++];
      if (!inserted.has(row.id)) {
        outcomes[i] = { kind: 'already-imported' };
        summary.ignored++;
        continue;
      }
      summary.added++;
      if (row.duplicateOfId) summary.duplicates++;
      else if (row.status === 'accepted') summary.accepted++;
    } else if (o.kind === 'unparsed') {
      summary.unread++;
    } else {
      summary.ignored++;
    }
  }
  return { summary, outcomes };
}

/** Full pipeline for one SMS: only configured kinds become transactions (pending in Review). */
export async function importSms(db: SQLiteDatabase, sms: RawSms): Promise<ImportOutcome> {
  const ctx = await loadContext(db, sms.receivedAt, sms.receivedAt);
  const { outcomes } = await importChunk(db, [sms], ctx, {});
  notifyDbChanged();
  return outcomes[0];
}

/** Imports many SMS (oldest first) in chunks, yielding to the UI between them. */
export async function importBatch(
  db: SQLiteDatabase,
  messages: RawSms[],
  opts: ImportOptions = {},
): Promise<ImportSummary> {
  const total = emptySummary();
  if (messages.length === 0) return total;

  let from = Infinity;
  let to = -Infinity;
  for (const m of messages) {
    if (m.receivedAt < from) from = m.receivedAt;
    if (m.receivedAt > to) to = m.receivedAt;
  }
  const ctx = await loadContext(db, from, to);
  for (let i = 0; i < messages.length; i += CHUNK) {
    const { summary } = await importChunk(db, messages.slice(i, i + CHUNK), ctx, opts);
    addSummary(total, summary);
    opts.onProgress?.({ ...total });
    await yieldToUi();
  }
  notifyDbChanged();
  return total;
}

/** Removes everything imported from SMS (manual transactions stay) before a fresh setup import. */
export async function clearSmsTransactions(db: SQLiteDatabase): Promise<number> {
  let removed = 0;
  await db.withTransactionAsync(async () => {
    const res = await db.runAsync(`DELETE FROM transactions WHERE source = 'sms'`);
    removed = res.changes;
    await db.runAsync('DELETE FROM unread_sms');
  });
  return removed;
}

export async function countSmsTransactions(db: SQLiteDatabase): Promise<number> {
  const row = await db.getFirstAsync<{ n: number }>(`SELECT COUNT(*) AS n FROM transactions WHERE source = 'sms'`);
  return row?.n ?? 0;
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
