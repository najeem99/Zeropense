import type { SQLiteDatabase } from 'expo-sqlite';

import { notifyDbChanged } from '@/db/live';
import { newId } from '@/lib/id';
import type { Currency } from '@/lib/money';

export type AccountKind = 'credit_card' | 'debit_card' | 'bank';

export const ACCOUNT_KIND_LABELS: Record<AccountKind, string> = {
  credit_card: 'Credit card',
  debit_card: 'Debit card',
  bank: 'Bank account',
};

export type Account = {
  id: string;
  name: string;
  kind: AccountKind;
  currency: Currency;
  /** Last 4 digits of the account/card as they appear in bank SMS. */
  smsHint: string | null;
};

/** "ENBD Platinum ••1234" */
export function accountLabel(a: Pick<Account, 'name' | 'smsHint'>): string {
  return a.smsHint ? `${a.name} ••${a.smsHint}` : a.name;
}

type Row = { id: string; name: string; kind: AccountKind; currency: Currency; sms_hint: string | null };

const toAccount = (r: Row): Account => ({
  id: r.id,
  name: r.name,
  kind: r.kind,
  currency: r.currency,
  smsHint: r.sms_hint,
});

export async function listAccounts(db: SQLiteDatabase): Promise<Account[]> {
  const rows = await db.getAllAsync<Row>('SELECT * FROM accounts ORDER BY kind, currency, name');
  return rows.map(toAccount);
}

/**
 * Adds the account and links every earlier transaction whose SMS named the same last
 * digits. Returns how many transactions were linked.
 */
export async function createAccount(db: SQLiteDatabase, input: Omit<Account, 'id'>): Promise<number> {
  const id = newId();
  const hint = input.smsHint?.trim().slice(-4) || null;
  let linked = 0;
  await db.withTransactionAsync(async () => {
    await db.runAsync(
      'INSERT INTO accounts (id, name, kind, currency, sms_hint, created_at) VALUES (?, ?, ?, ?, ?, ?)',
      id,
      input.name.trim(),
      input.kind,
      input.currency,
      hint,
      Date.now(),
    );
    if (hint) {
      const res = await db.runAsync(
        `UPDATE transactions SET account_id = ? WHERE account_id IS NULL AND substr(account_hint, -4) = ?`,
        id,
        hint,
      );
      linked = res.changes;
    }
  });
  notifyDbChanged();
  return linked;
}

/** Transactions keep their data; they just lose the account link. */
export async function deleteAccount(db: SQLiteDatabase, id: string): Promise<void> {
  await db.runAsync('DELETE FROM accounts WHERE id = ?', id);
  notifyDbChanged();
}

/** Card/account digits seen in SMS that aren't linked to any account yet. */
export type DetectedCard = {
  digits: string;
  currency: Currency;
  count: number;
  lastSender: string | null;
  lastAt: number;
};

export async function listDetectedCards(db: SQLiteDatabase): Promise<DetectedCard[]> {
  return db.getAllAsync<DetectedCard>(
    `SELECT substr(t.account_hint, -4) AS digits, t.currency AS currency, COUNT(*) AS count,
            MAX(t.occurred_at) AS lastAt,
            (SELECT sms_sender FROM transactions t2 WHERE substr(t2.account_hint, -4) = substr(t.account_hint, -4)
             ORDER BY occurred_at DESC LIMIT 1) AS lastSender
     FROM transactions t
     WHERE t.account_hint IS NOT NULL AND t.account_id IS NULL AND t.status != 'rejected'
       AND substr(t.account_hint, -4) NOT IN (SELECT sms_hint FROM accounts WHERE sms_hint IS NOT NULL)
     GROUP BY digits, t.currency
     ORDER BY count DESC`,
  );
}

/** Links an existing account to the card digits found in SMS (and its earlier transactions). Caller notifies. */
export async function setAccountHint(db: SQLiteDatabase, id: string, hint: string): Promise<void> {
  await db.runAsync('UPDATE accounts SET sms_hint = ? WHERE id = ?', hint.slice(-4), id);
  await db.runAsync(
    'UPDATE transactions SET account_id = ? WHERE account_id IS NULL AND substr(account_hint, -4) = ?',
    id,
    hint.slice(-4),
  );
}
