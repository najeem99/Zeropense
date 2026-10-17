import type { Currency, Direction } from '@/lib/money';

import { findAmountCandidates, findDigitsCandidates } from './candidates';
import { findAccount, findDate, findDirection, findMerchant } from './extract';
import { PERSONAL_SENDER, skipReason } from './filters';
import { parseSms } from './parse';
import type { ParsedSms, RawSms } from './types';

/**
 * One bank sender under its different operator prefixes/suffixes:
 * "VM-HDFCBK", "AD-HDFCBK-S" and "HDFCBK" are all "HDFCBK".
 */
export function senderKey(sender: string): string {
  return sender
    .trim()
    .toUpperCase()
    .replace(/^[A-Z]{2}-/, '')
    .replace(/-[A-Z]$/, '');
}

export function isPersonalSender(sender: string): boolean {
  return PERSONAL_SENDER.test(sender.replace(/[\s-]/g, ''));
}

const TEMPLATE_WORDS = 8;

/**
 * The fixed opening of a message with numbers masked, used to group one sender's SMS
 * into kinds: "Purchase of AED 45.50 with Credit Card ending 1234 at…" →
 * "purchase of aed # with credit card ending".
 */
export function templateKey(body: string): string {
  return body
    .toLowerCase()
    .replace(/\d[\d,]*(?:\.\d+)?/g, '#')
    .replace(/[^a-z#]+/g, ' ')
    .trim()
    .split(' ')
    .slice(0, TEMPLATE_WORDS)
    .join(' ');
}

/** A message kind the user configured during SMS setup. */
export type SmsRule = {
  id: string;
  senderKey: string;
  templateKey: string;
  /** 'import' kinds become transactions; 'ignore' kinds are known non-transactions (OTP, promos). */
  action: 'import' | 'ignore';
  /** Which amount in the message is the transaction, by position. */
  amountIndex: number;
  direction: Direction;
  currency: Currency;
  /** For kinds that never name a card: the account to use. */
  fixedAccountId: string | null;
  sample: string;
};

export function ruleFor(sms: RawSms, rules: SmsRule[]): SmsRule | null {
  const s = senderKey(sms.sender);
  const t = templateKey(sms.body);
  return rules.find((r) => r.senderKey === s && r.templateKey === t) ?? null;
}

/** Reads a message with an 'import' rule. Position-based, so new merchants and amounts still work. */
export function applyRule(rule: SmsRule, sms: RawSms): ParsedSms | null {
  if (rule.action !== 'import') return null;
  const amount = findAmountCandidates(sms.body)[rule.amountIndex];
  if (!amount) return null;
  return {
    amountMinor: amount.minor,
    currency: amount.currency ?? rule.currency,
    direction: rule.direction,
    merchant: findMerchant(sms.body, rule.direction),
    accountHint: findAccount(sms.body) ?? findDigitsCandidates(sms.body)[0]?.digits.slice(-4) ?? null,
    occurredAt: findDate(sms.body),
    parserId: `rule:${rule.id}`,
  };
}

export type KindLabel = 'transaction' | 'otp' | 'promo' | 'reminder' | 'request' | 'failed' | 'money' | 'other';

export const KIND_LABELS: Record<KindLabel, string> = {
  transaction: 'Transaction',
  otp: 'OTP',
  promo: 'Promotional',
  reminder: 'Reminder',
  request: 'Payment request',
  failed: 'Failed / declined',
  money: 'Has an amount',
  other: 'Other',
};

const REASON_LABELS: Record<string, KindLabel> = {
  'OTP / verification code': 'otp',
  Promotional: 'promo',
  'Upcoming debit reminder': 'reminder',
  'Payment request, not a payment': 'request',
  'Failed or declined': 'failed',
};

export type KindSuggestion = {
  label: KindLabel;
  amountIndex: number;
  direction: Direction;
  currency: Currency;
};

/** Guesses what a kind of message is from one sample, to pre-fill the setup. */
export function suggestKind(sample: RawSms): KindSuggestion {
  const amounts = findAmountCandidates(sample.body);
  const firstReal = Math.max(0, amounts.findIndex((a) => !a.looksLikeBalance));
  const fallback: KindSuggestion = {
    label: amounts.length ? 'money' : 'other',
    amountIndex: firstReal,
    direction: findDirection(sample.body) ?? 'debit',
    currency: amounts[firstReal]?.currency ?? 'AED',
  };

  const reason = skipReason(sample.body);
  if (reason) return { ...fallback, label: REASON_LABELS[reason] ?? 'other' };

  const parsed = parseSms(sample, []);
  if (!parsed) return fallback;
  const index = amounts.findIndex((a) => a.minor === parsed.amountMinor);
  return {
    label: 'transaction',
    amountIndex: index === -1 ? firstReal : index,
    direction: parsed.direction,
    currency: parsed.currency,
  };
}

export type MessageKind = {
  templateKey: string;
  messages: RawSms[];
  suggestion: KindSuggestion;
  /** Distinct last-4 card/account digits seen across the kind's messages. */
  digits: string[];
};

export type SenderGroup = {
  senderKey: string;
  /** Raw addresses as the phone stores them, for reading the inbox by sender. */
  addresses: string[];
  kinds: MessageKind[];
};

/** Groups messages by sender, then by kind (largest first). */
export function groupMessages(messages: RawSms[]): SenderGroup[] {
  const senders = new Map<string, { addresses: Set<string>; kinds: Map<string, RawSms[]> }>();
  for (const m of messages) {
    const sk = senderKey(m.sender);
    let s = senders.get(sk);
    if (!s) senders.set(sk, (s = { addresses: new Set(), kinds: new Map() }));
    s.addresses.add(m.sender);
    const tk = templateKey(m.body);
    const list = s.kinds.get(tk);
    if (list) list.push(m);
    else s.kinds.set(tk, [m]);
  }
  return [...senders.entries()].map(([sk, s]) => ({
    senderKey: sk,
    addresses: [...s.addresses],
    kinds: [...s.kinds.entries()]
      .map(([tk, msgs]) => {
        const digits = new Set<string>();
        for (const m of msgs) {
          const d = findAccount(m.body) ?? findDigitsCandidates(m.body)[0]?.digits.slice(-4);
          if (d) digits.add(d);
        }
        // The newest message is the most representative sample of the current format.
        return { templateKey: tk, messages: msgs, suggestion: suggestKind(msgs[msgs.length - 1]), digits: [...digits] };
      })
      .sort((a, b) => b.messages.length - a.messages.length),
  }));
}
