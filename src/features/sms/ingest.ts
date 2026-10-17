import { suggestCategory, type MerchantRule } from '@/features/categories/categorizer';
import type { DedupeIndex } from '@/features/transactions/dedupe';
import { hashString } from '@/lib/id';
import type { Currency, Direction } from '@/lib/money';

import { findAmountCandidates } from './candidates';
import { applyCustomParser, type CustomParser } from './custom-parser';
import { skipReason } from './filters';
import { applyRule, ruleFor, senderKey, type SmsRule } from './templates';
import type { ParsedSms, RawSms } from './types';

export type AccountRef = { id: string; currency: Currency; smsHint: string | null };

export type IngestContext = {
  /** Message kinds configured in SMS setup. Only these and custom parsers are imported. */
  smsRules: SmsRule[];
  customParsers: CustomParser[];
  accounts: AccountRef[];
  /** Existing transactions, for duplicate detection. New drafts are added to it. */
  dedupe: DedupeIndex;
  rules: MerchantRule[];
};

/** A transaction ready to be saved (pending in Review, or accepted by SMS setup). */
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
  /** Not a configured kind of message. Nothing is stored. */
  | { kind: 'ignored'; reason: string }
  /** From a configured bank sender and has an amount, but no configured kind matched: worth setting up. */
  | { kind: 'unparsed' }
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

function isConfiguredSender(sms: RawSms, ctx: IngestContext): boolean {
  const key = senderKey(sms.sender);
  const lower = sms.sender.toLowerCase();
  return (
    ctx.smsRules.some((r) => r.senderKey === key) ||
    ctx.customParsers.some((p) => p.senderContains && lower.includes(p.senderContains.toLowerCase()))
  );
}

/**
 * Pure decision step. Only message kinds the user configured (SMS setup rules, then custom
 * parsers) become transactions; everything else is ignored.
 */
export function processSms(sms: RawSms, ctx: IngestContext): IngestResult {
  let parsed: ParsedSms | null = null;
  let fixedAccountId: string | null = null;

  const rule = ruleFor(sms, ctx.smsRules);
  if (rule) {
    if (rule.action === 'ignore') return { kind: 'ignored', reason: 'You chose to ignore this kind of message' };
    parsed = applyRule(rule, sms);
    fixedAccountId = rule.fixedAccountId;
  }
  if (!parsed) {
    for (const p of ctx.customParsers) {
      parsed = applyCustomParser(p, sms);
      if (parsed) break;
    }
  }
  if (!parsed) {
    const configured = isConfiguredSender(sms, ctx);
    if (configured && !skipReason(sms.body) && findAmountCandidates(sms.body).length > 0) return { kind: 'unparsed' };
    return { kind: 'ignored', reason: configured ? 'Not a transaction' : 'Not a configured bank SMS' };
  }

  // SMS dates rarely carry a time; the receive time is more precise on the same day,
  // and duplicate detection depends on it.
  const occurredAt =
    parsed.occurredAt && !isSameDay(parsed.occurredAt, sms.receivedAt) ? parsed.occurredAt : sms.receivedAt;
  // Card digits in the SMS win; kinds that never name a card use the account chosen in setup.
  const byDigits = parsed.accountHint ? matchAccount(parsed.accountHint, parsed.currency, ctx.accounts) : null;
  const accountId =
    byDigits ?? fixedAccountId ?? (parsed.accountHint ? null : matchAccount(null, parsed.currency, ctx.accounts));
  const duplicateOfId = ctx.dedupe.find({
    amountMinor: parsed.amountMinor,
    currency: parsed.currency,
    direction: parsed.direction,
    occurredAt,
    accountId,
  });

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
