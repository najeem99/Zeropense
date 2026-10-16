/**
 * Runs the real migrations and repository SQL against Node's built-in SQLite
 * (node:sqlite, unflagged from Node 22.13). Skipped on older Node.
 */
import type { SQLiteDatabase } from 'expo-sqlite';

import { createAccount, listAccounts, listDetectedCards } from '@/features/accounts/repository';
import { listRules } from '@/features/categories/repository';
import { createCustomParser, importBatch, importSms, listUnread, recheckUnread } from '@/features/sms/repository';
import { buildPattern } from '@/features/sms/custom-parser';
import { listPending, listSummaryInputs, saveTransaction, setStatus } from '@/features/transactions/repository';

import { migrate } from '../migrations';

jest.mock('expo-sqlite', () => ({ useSQLiteContext: jest.fn() }));

type NodeSqlite = {
  DatabaseSync: new (path: string) => {
    exec(sql: string): void;
    prepare(sql: string): {
      run(...p: unknown[]): { changes: number | bigint; lastInsertRowid: number | bigint };
      all(...p: unknown[]): unknown[];
      get(...p: unknown[]): unknown;
    };
  };
};

function loadSqlite(): NodeSqlite | null {
  try {
    // Must be a runtime require so older Node can skip instead of failing to load the file.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return require('node:sqlite') as NodeSqlite;
  } catch {
    return null;
  }
}

const sqlite = loadSqlite();

/** Just enough of expo-sqlite's async API for the repositories. */
function openTestDb(lib: NodeSqlite): SQLiteDatabase {
  const raw = new lib.DatabaseSync(':memory:');
  const args = (p: unknown[]) => (p.length === 1 && Array.isArray(p[0]) ? p[0] : p);
  const db = {
    execAsync: async (sql: string) => raw.exec(sql),
    runAsync: async (sql: string, ...p: unknown[]) => {
      const r = raw.prepare(sql).run(...args(p));
      return { changes: Number(r.changes), lastInsertRowId: Number(r.lastInsertRowid) };
    },
    getAllAsync: async (sql: string, ...p: unknown[]) => raw.prepare(sql).all(...args(p)),
    getFirstAsync: async (sql: string, ...p: unknown[]) => raw.prepare(sql).get(...args(p)) ?? null,
    withTransactionAsync: async (fn: () => Promise<void>) => {
      raw.exec('BEGIN');
      try {
        await fn();
        raw.exec('COMMIT');
      } catch (e) {
        raw.exec('ROLLBACK');
        throw e;
      }
    },
  };
  return db as unknown as SQLiteDatabase;
}

const t0 = new Date(2026, 9, 11, 13, 0).getTime();
const enbd = (amount: string, digits: string, minutes = 0) => ({
  sender: 'EmiratesNBD',
  body: `Purchase of AED ${amount} with Credit Card ending ${digits} at CARREFOUR, DUBAI. Avl Cr. Limit is AED 9,000.00`,
  receivedAt: t0 + minutes * 60_000,
});

(sqlite ? describe : describe.skip)('repositories on SQLite', () => {
  let db: SQLiteDatabase;
  beforeEach(async () => {
    db = openTestDb(sqlite!);
    await migrate(db);
  });

  it('migrates idempotently', async () => {
    await migrate(db);
    const v = await db.getFirstAsync<{ user_version: number }>('PRAGMA user_version');
    expect(v?.user_version).toBe(2);
  });

  it('imports a batch, flags duplicates and never re-imports the same SMS', async () => {
    const batch = [enbd('45.50', '1234'), enbd('45.50', '1234', 2), enbd('10.00', '1234', 30)];
    expect(await importBatch(db, batch)).toMatchObject({ read: 3, added: 3, duplicates: 1 });
    expect(await importBatch(db, batch)).toMatchObject({ added: 0 });
    expect(await listPending(db)).toHaveLength(3);
  });

  it('accepts history imports straight into totals but keeps duplicates for review', async () => {
    const batch = [enbd('45.50', '1234'), enbd('45.50', '1234', 1), enbd('12.00', '1234', 300)];
    expect(await importBatch(db, batch, { autoAccept: true })).toMatchObject({ added: 3, accepted: 2, duplicates: 1 });
    expect(await listPending(db)).toHaveLength(1);
    const totals = await listSummaryInputs(db, 0, Number.MAX_SAFE_INTEGER);
    expect(totals.reduce((sum, t) => sum + t.amountMinor, 0)).toBe(5750);
  });

  it('detects unknown cards and links past transactions when the card is added', async () => {
    await importBatch(db, [enbd('45.50', '1234'), enbd('12.00', '1234', 60), enbd('5.00', '7777', 120)]);
    const detected = await listDetectedCards(db);
    expect(detected.map((d) => [d.digits, d.count, d.lastSender])).toEqual([
      ['1234', 2, 'EmiratesNBD'],
      ['7777', 1, 'EmiratesNBD'],
    ]);

    const linked = await createAccount(db, { name: 'ENBD', kind: 'credit_card', currency: 'AED', smsHint: '1234' });
    expect(linked).toBe(2);
    expect((await listAccounts(db))[0]).toMatchObject({ kind: 'credit_card', smsHint: '1234' });
    expect((await listDetectedCards(db)).map((d) => d.digits)).toEqual(['7777']);

    // New SMS for that card now link straight away.
    const r = await importSms(db, enbd('3.00', '1234', 300));
    expect(r.kind === 'draft' && r.draft.accountId).toBeTruthy();
  });

  it('learns categories on accept and applies them to the next SMS', async () => {
    await importSms(db, enbd('45.50', '1234'));
    const [tx] = await listPending(db);
    await saveTransaction(db, tx.id, { ...tx, categoryId: 'groceries' });
    expect(await listRules(db)).toEqual([{ merchantKey: 'CARREFOUR', categoryId: 'groceries' }]);

    const next = await importSms(db, enbd('8.00', '1234', 120));
    expect(next.kind === 'draft' && next.draft.categoryId).toBe('groceries');

    await setStatus(db, [(await listPending(db))[0].id], 'accepted');
    expect(await listPending(db)).toHaveLength(0);
  });

  it('keeps unreadable financial SMS and re-reads them after a format is set up', async () => {
    const odd = { sender: 'MyBank', body: 'Card 4455 used. Amt: 87.25 AED. Shop: LULU.', receivedAt: t0 };
    // "used" isn't a debit word, so nothing reads it yet.
    expect(await importSms(db, odd)).toEqual({ kind: 'unparsed', looksFinancial: true });
    expect(await listUnread(db)).toHaveLength(1);

    const start = odd.body.indexOf('87.25');
    const built = buildPattern(odd.body, [{ field: 'amount', start, end: start + 5 }]);
    if (!built.ok) throw new Error(built.error);
    await createCustomParser(db, {
      name: 'MyBank',
      senderContains: 'MyBank',
      pattern: built.pattern,
      fields: built.fields,
      currency: 'AED',
      direction: 'debit',
      sample: odd.body,
    });

    expect(await recheckUnread(db)).toMatchObject({ added: 1 });
    expect(await listUnread(db)).toHaveLength(0);
    expect((await listPending(db))[0]).toMatchObject({ amountMinor: 8725, smsSender: 'MyBank' });
  });
});
