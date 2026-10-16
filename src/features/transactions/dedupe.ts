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
