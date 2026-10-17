import type { Currency, Direction } from '@/lib/money';

export type DedupeCandidate = {
  id: string;
  amountMinor: number;
  currency: Currency;
  direction: Direction;
  occurredAt: number;
  accountId: string | null;
};

/** Banks and UPI apps often both send an SMS for one payment, minutes apart. */
export const DUPLICATE_WINDOW_MS = 15 * 60 * 1000;

/**
 * Returns the id of an existing transaction this one probably duplicates.
 * Two genuine identical payments look the same, so a match is only a flag for the
 * review queue, never an automatic delete.
 */
export function findDuplicate(
  candidate: Omit<DedupeCandidate, 'id'>,
  existing: DedupeCandidate[],
  windowMs = DUPLICATE_WINDOW_MS,
): string | null {
  let best: { id: string; gap: number } | null = null;
  for (const e of existing) {
    if (e.amountMinor !== candidate.amountMinor) continue;
    if (e.currency !== candidate.currency || e.direction !== candidate.direction) continue;
    if (e.accountId && candidate.accountId && e.accountId !== candidate.accountId) continue;
    const gap = Math.abs(e.occurredAt - candidate.occurredAt);
    if (gap <= windowMs && (!best || gap < best.gap)) best = { id: e.id, gap };
  }
  return best?.id ?? null;
}

/**
 * Candidates bucketed by amount, currency and direction, so checking one SMS against
 * thousands of transactions doesn't scan them all. Add to it as new drafts are created.
 */
export class DedupeIndex {
  private buckets = new Map<string, DedupeCandidate[]>();

  constructor(items: DedupeCandidate[] = []) {
    items.forEach((i) => this.add(i));
  }

  private static key(c: Pick<DedupeCandidate, 'amountMinor' | 'currency' | 'direction'>): string {
    return `${c.amountMinor}|${c.currency}|${c.direction}`;
  }

  add(item: DedupeCandidate): void {
    const k = DedupeIndex.key(item);
    const bucket = this.buckets.get(k);
    if (bucket) bucket.push(item);
    else this.buckets.set(k, [item]);
  }

  find(candidate: Omit<DedupeCandidate, 'id'>, windowMs = DUPLICATE_WINDOW_MS): string | null {
    return findDuplicate(candidate, this.buckets.get(DedupeIndex.key(candidate)) ?? [], windowMs);
  }
}
