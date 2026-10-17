import { router } from 'expo-router';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Amount } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import { accountLabel, listAccounts, type Account } from '@/features/accounts/repository';
import { CategoryIcon, CategoryPill } from '@/features/categories/category-icon';
import { listCategories, type Category } from '@/features/categories/repository';
import { smsSnippet } from '@/features/sms/extract';
import { formatDate } from '@/lib/dates';
import { spacing, useColors } from '@/theme';

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

/** What a row is called: the merchant, else a short snippet of the SMS (shown smaller), else the direction. */
export function transactionTitle(tx: Pick<Transaction, 'merchant' | 'smsBody' | 'direction'>): {
  text: string;
  isSnippet: boolean;
} {
  if (tx.merchant) return { text: tx.merchant, isSnippet: false };
  if (tx.smsBody) return { text: smsSnippet(tx.smsBody), isSnippet: true };
  return { text: tx.direction === 'credit' ? 'Money in' : 'Money out', isSnippet: true };
}

export function TransactionRow({ tx, lookups }: { tx: Transaction; lookups: Lookups }) {
  const c = useColors();
  const title = transactionTitle(tx);
  const category = lookups.categoryName(tx.categoryId) ?? 'Uncategorised';
  const meta = [lookups.cardName(tx), formatDate(tx.occurredAt)].filter(Boolean).join(' · ');
  return (
    <Pressable
      onPress={() => router.push({ pathname: '/transaction/[id]', params: { id: tx.id } })}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.md,
        paddingVertical: spacing.sm,
        borderBottomWidth: StyleSheet.hairlineWidth,
        borderColor: c.border,
        opacity: pressed ? 0.6 : 1,
      })}>
      <CategoryIcon categoryId={tx.categoryId} />
      <View style={{ flex: 1, gap: 3 }}>
        <Text
          numberOfLines={1}
          style={
            title.isSnippet
              ? { fontSize: 13, color: c.textMuted, fontStyle: 'italic' }
              : { fontSize: 15, fontWeight: '600', color: c.text }
          }>
          {title.text}
        </Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
          <CategoryPill categoryId={tx.categoryId} name={category} />
          <Text numberOfLines={1} style={{ flex: 1, fontSize: 12, color: c.textMuted }}>
            {meta}
          </Text>
        </View>
      </View>
      <Amount minor={tx.amountMinor} currency={tx.currency} direction={tx.direction} />
    </Pressable>
  );
}
