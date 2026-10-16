import { suggestCategory, type MerchantRule } from '@/features/categories/categorizer';
import { findDuplicate, type DedupeCandidate } from '@/features/transactions/dedupe';
import { hashString } from '@/lib/id';
import type { Currency, Direction } from '@/lib/money';

import { findAmountCandidates } from './candidates';
import type { CustomParser } from './custom-parser';
import { skipReason } from './filters';
import { parseSms } from './parse';
import type { RawSms } from './types';

export type AccountRef = { id: string; currency: Currency; smsHint: string | null };

export type IngestContext = {
  customParsers: CustomParser[];
  accounts: AccountRef[];
  /** Transactions near this SMS's time, for duplicate detection. */
  recent: DedupeCandidate[];
  rules: MerchantRule[];
};

/** A transaction ready to be saved as "pending" in the review queue. */
export type TransactionDraft = {
  amountMinor: number;
  currency: Currency;
  direction: Direction;
  merchant: string | null;
  occurredAt: number;
  accountId: string | null;
  /** Last digits of the card/account named in the SMS, kept so unknown cards can be added later. */
  accountHint: string | null;
  categoryId: string | null;
  duplicateOfId: string | null;
  smsHash: string;
  smsSender: string;
  smsBody: string;
  parserId: string;
};

export type IngestResult =
  | { kind: 'skipped'; reason: string }
  /** `looksFinancial`: has an amount, so worth showing in SMS configuration. */
  | { kind: 'unparsed'; looksFinancial: boolean }
  | { kind: 'draft'; draft: TransactionDraft };

function isSameDay(a: number, b: number): boolean {
  return new Date(a).toDateString() === new Date(b).toDateString();
}

export function smsHash(sms: RawSms): string {
  return hashString(`${sms.sender}|${sms.receivedAt}|${sms.body}`);
}

/**
 * The account whose last digits appear in the SMS. Digits that match no account mean a
 * card the user hasn't added yet, so stay unlinked. Without digits, fall back to the
 * only account in that currency.
 */
export function matchAccount(hint: string | null, currency: Currency, accounts: AccountRef[]): string | null {
  if (hint) {
    return accounts.find((a) => a.smsHint && hint.slice(-4) === a.smsHint.slice(-4))?.id ?? null;
  }
  const sameCurrency = accounts.filter((a) => a.currency === currency);
  return sameCurrency.length === 1 ? sameCurrency[0].id : null;
}

/** Pure decision step: turns one SMS into a review-queue draft, or says why not. */
export function processSms(sms: RawSms, ctx: IngestContext): IngestResult {
  const reason = skipReason(sms.body, sms.sender);
  if (reason) return { kind: 'skipped', reason };

  const parsed = parseSms(sms, ctx.customParsers);
  if (!parsed) return { kind: 'unparsed', looksFinancial: findAmountCandidates(sms.body).length > 0 };

  // SMS dates rarely carry a time; the receive time is more precise on the same day,
  // and duplicate detection depends on it.
  const occurredAt =
    parsed.occurredAt && !isSameDay(parsed.occurredAt, sms.receivedAt) ? parsed.occurredAt : sms.receivedAt;
  const accountId = matchAccount(parsed.accountHint, parsed.currency, ctx.accounts);
  const duplicateOfId = findDuplicate(
    { amountMinor: parsed.amountMinor, currency: parsed.currency, direction: parsed.direction, occurredAt, accountId },
    ctx.recent,
  );

  return {
    kind: 'draft',
    draft: {
      amountMinor: parsed.amountMinor,
      currency: parsed.currency,
      direction: parsed.direction,
      merchant: parsed.merchant,
      occurredAt,
      accountId,
      accountHint: parsed.accountHint,
      categoryId: suggestCategory(parsed.merchant, ctx.rules),
      duplicateOfId,
      smsHash: smsHash(sms),
      smsSender: sms.sender,
      smsBody: sms.body,
      parserId: parsed.parserId,
    },
  };
}
