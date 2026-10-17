import type { SQLiteDatabase } from 'expo-sqlite';

import { notifyDbChanged } from '@/db/live';
import { newId } from '@/lib/id';
import type { Currency, Direction } from '@/lib/money';

import { templateKey, type SmsRule } from './templates';

type Row = {
  id: string;
  sender_key: string;
  template_key: string;
  action: 'import' | 'ignore';
  amount_index: number;
  direction: Direction;
  currency: Currency;
  fixed_account_id: string | null;
  sample: string;
};

export async function listSmsRules(db: SQLiteDatabase): Promise<SmsRule[]> {
  const rows = await db.getAllAsync<Row>('SELECT * FROM sms_rules ORDER BY sender_key, created_at');
  return rows.map((r) => ({
    id: r.id,
    senderKey: r.sender_key,
    // Recomputed from the sample so rules keep matching if the key format changes.
    templateKey: templateKey(r.sample),
    action: r.action,
    amountIndex: r.amount_index,
    direction: r.direction,
    currency: r.currency,
    fixedAccountId: r.fixed_account_id,
    sample: r.sample,
  }));
}

/**
 * Saves the setup's choices for the given senders. Earlier rules for those senders are
 * replaced; rules for other senders are kept. Caller notifies.
 */
export async function replaceSmsRules(
  db: SQLiteDatabase,
  senderKeys: string[],
  rules: Omit<SmsRule, 'id'>[],
): Promise<void> {
  await db.withTransactionAsync(async () => {
    for (const key of senderKeys) await db.runAsync('DELETE FROM sms_rules WHERE sender_key = ?', key);
    const now = Date.now();
    for (const r of rules) {
      await db.runAsync(
        `INSERT OR REPLACE INTO sms_rules
          (id, sender_key, template_key, action, amount_index, direction, currency, fixed_account_id, sample, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        newId(),
        r.senderKey,
        r.templateKey,
        r.action,
        r.amountIndex,
        r.direction,
        r.currency,
        r.fixedAccountId,
        r.sample,
        now,
      );
    }
  });
}

export async function deleteSmsRule(db: SQLiteDatabase, id: string): Promise<void> {
  await db.runAsync('DELETE FROM sms_rules WHERE id = ?', id);
  notifyDbChanged();
}
