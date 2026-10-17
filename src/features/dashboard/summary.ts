import { REFUND_CATEGORY } from '@/features/categories/categorizer';
import type { Currency, Direction } from '@/lib/money';

export type SummaryInput = {
  amountMinor: number;
  currency: Currency;
  direction: Direction;
  categoryId: string | null;
};

export type CurrencySummary = {
  currency: Currency;
  /** Income, without refunds. */
  inMinor: number;
  /** Spending minus refunds. */
  outMinor: number;
  refundMinor: number;
  netMinor: number;
  /** Spending by category, largest first. null = uncategorised. */
  topSpending: { categoryId: string | null; minor: number }[];
};

/** Totals per currency. AED and INR are never added together. */
export function summarize(txs: SummaryInput[]): CurrencySummary[] {
  const by = new Map<Currency, { in: number; out: number; refund: number; cats: Map<string | null, number> }>();
  for (const t of txs) {
    let s = by.get(t.currency);
    if (!s) by.set(t.currency, (s = { in: 0, out: 0, refund: 0, cats: new Map() }));
    if (t.direction === 'credit' && t.categoryId === REFUND_CATEGORY) {
      s.refund += t.amountMinor;
    } else if (t.direction === 'credit') {
      s.in += t.amountMinor;
    } else {
      s.out += t.amountMinor;
      s.cats.set(t.categoryId, (s.cats.get(t.categoryId) ?? 0) + t.amountMinor);
    }
  }
  return [...by.entries()]
    .map(([currency, s]) => ({
      currency,
      inMinor: s.in,
      outMinor: s.out - s.refund,
      refundMinor: s.refund,
      netMinor: s.in - (s.out - s.refund),
      topSpending: [...s.cats.entries()]
        .map(([categoryId, minor]) => ({ categoryId, minor }))
        .sort((a, b) => b.minor - a.minor),
    }))
    .sort((a, b) => a.currency.localeCompare(b.currency));
}
