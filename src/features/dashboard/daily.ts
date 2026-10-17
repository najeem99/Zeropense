import type { Currency } from '@/lib/money';

import type { SummaryInput } from './summary';

const DAY = 24 * 60 * 60 * 1000;

export function startOfDay(ts: number): number {
  const d = new Date(ts);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

export function dayAt(from: number, i: number): number {
  const d = new Date(from);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + i).getTime();
}

export type DailySpending = {
  /** Money out per day, index 0 = the day of `from`. */
  days: number[];
  /** Average over the days up to today (not the whole month while it is still running). */
  averageMinor: number;
  peak: { index: number; minor: number } | null;
};

/** Spending (money out) per calendar day for one currency. */
export function dailySpending(
  txs: (SummaryInput & { occurredAt: number })[],
  currency: Currency,
  from: number,
  count: number,
  today: number,
): DailySpending {
  const start = startOfDay(from);
  const days = new Array<number>(count).fill(0);
  for (const t of txs) {
    if (t.currency !== currency || t.direction !== 'debit') continue;
    // Rounding absorbs a daylight-saving hour.
    const i = Math.round((startOfDay(t.occurredAt) - start) / DAY);
    if (i >= 0 && i < count) days[i] += t.amountMinor;
  }
  const elapsed = Math.min(count, Math.max(1, Math.round((startOfDay(today) - start) / DAY) + 1));
  const total = days.slice(0, elapsed).reduce((sum, m) => sum + m, 0);
  let peak: DailySpending['peak'] = null;
  for (let index = 0; index < count; index++) {
    if (days[index] > 0 && (!peak || days[index] > peak.minor)) peak = { index, minor: days[index] };
  }
  return { days, averageMinor: Math.round(total / elapsed), peak };
}
