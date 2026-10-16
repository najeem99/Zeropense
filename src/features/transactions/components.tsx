import { router } from 'expo-router';

import { Amount, Row } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { accountLabel, listAccounts, type Account } from '@/features/accounts/repository';
import { listCategories, type Category } from '@/features/categories/repository';
import { formatDate } from '@/lib/dates';

import type { Transaction } from './repository';

export type Lookups = {
  accounts: Account[];
  categories: Category[];
  accountName: (id: string | null) => string | null;
  categoryName: (id: string | null) => string | null;
  /** The card/account a transaction used: its label, or "Card ••1234" if not added yet. */
  cardName: (tx: Pick<Transaction, 'accountId' | 'accountHint'>) => string | null;
};

/** Accounts and categories, kept fresh, with id → name helpers. */
export function useLookups(): Lookups | undefined {
  const data = useDbQuery(async (db) => {
    const [accounts, categories] = await Promise.all([listAccounts(db), listCategories(db)]);
    return { accounts, categories };
  });
  if (!data) return undefined;
  const accounts = new Map(data.accounts.map((a) => [a.id, accountLabel(a)]));
  const categories = new Map(data.categories.map((c) => [c.id, c.name]));
  const accountName = (id: string | null) => (id ? (accounts.get(id) ?? null) : null);
  return {
    ...data,
    accountName,
    categoryName: (id) => (id ? (categories.get(id) ?? null) : null),
    cardName: (tx) => accountName(tx.accountId) ?? (tx.accountHint ? `Card ••${tx.accountHint.slice(-4)}` : null),
  };
}

export function TransactionRow({ tx, lookups }: { tx: Transaction; lookups: Lookups }) {
  const subtitle = [
    lookups.categoryName(tx.categoryId) ?? 'Uncategorised',
    lookups.cardName(tx),
    formatDate(tx.occurredAt),
  ]
    .filter(Boolean)
    .join(' · ');
  return (
    <Row
      title={tx.merchant ?? (tx.direction === 'credit' ? 'Money in' : 'Money out')}
      subtitle={subtitle}
      right={<Amount minor={tx.amountMinor} currency={tx.currency} direction={tx.direction} />}
      onPress={() => router.push({ pathname: '/transaction/[id]', params: { id: tx.id } })}
    />
  );
}
