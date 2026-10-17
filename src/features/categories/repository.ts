import type { SQLiteDatabase } from 'expo-sqlite';

import { notifyDbChanged } from '@/db/live';
import { newId } from '@/lib/id';

import { merchantKey, REFUND_CATEGORY, type MerchantRule } from './categorizer';

export type CategoryKind = 'expense' | 'income';
export type Category = { id: string; name: string; kind: CategoryKind };

export async function listCategories(db: SQLiteDatabase): Promise<Category[]> {
  return db.getAllAsync<Category>('SELECT id, name, kind FROM categories ORDER BY kind, sort, name');
}

export async function createCategory(db: SQLiteDatabase, name: string, kind: CategoryKind): Promise<void> {
  const max = await db.getFirstAsync<{ m: number | null }>('SELECT MAX(sort) AS m FROM categories');
  await db.runAsync(
    'INSERT INTO categories (id, name, kind, sort) VALUES (?, ?, ?, ?)',
    newId(),
    name.trim(),
    kind,
    (max?.m ?? 0) + 1,
  );
  notifyDbChanged();
}

/** Transactions become uncategorised; learned rules for it are dropped. */
export async function deleteCategory(db: SQLiteDatabase, id: string): Promise<void> {
  await db.runAsync('DELETE FROM categories WHERE id = ?', id);
  notifyDbChanged();
}

export async function listRules(db: SQLiteDatabase): Promise<MerchantRule[]> {
  return db.getAllAsync<MerchantRule>(
    'SELECT merchant_key AS merchantKey, category_id AS categoryId FROM merchant_rules ORDER BY merchant_key',
  );
}

/** Remember "this merchant → this category" for future SMS. Caller notifies. */
export async function learnRule(db: SQLiteDatabase, merchant: string | null, categoryId: string | null): Promise<void> {
  if (!merchant || !categoryId || categoryId === REFUND_CATEGORY) return;
  const key = merchantKey(merchant);
  if (!key) return;
  await db.runAsync(
    `INSERT INTO merchant_rules (merchant_key, category_id, updated_at) VALUES (?, ?, ?)
     ON CONFLICT (merchant_key) DO UPDATE SET category_id = excluded.category_id, updated_at = excluded.updated_at`,
    key,
    categoryId,
    Date.now(),
  );
}

export async function deleteRule(db: SQLiteDatabase, key: string): Promise<void> {
  await db.runAsync('DELETE FROM merchant_rules WHERE merchant_key = ?', key);
  notifyDbChanged();
}
