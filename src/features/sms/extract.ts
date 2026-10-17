import { parseSmsDate } from '@/lib/dates';
import { normalizeCurrency, parseAmountToMinor, type Currency, type Direction } from '@/lib/money';

import type { ParsedSms } from './types';

/** Extra patterns a bank profile can try before the generic ones. Group 1 is the value. */
export type ExtractHints = {
  defaultCurrency?: Currency;
  /** Patterns whose group 1 is an amount with no currency next to it, e.g. SBI "debited by 150.0". */
  amount?: RegExp[];
  merchant?: RegExp[];
};

const NUM = String.raw`(\d[\d,]*(?:\.\d{1,2})?)`;
const CUR = String.raw`(INR|Rs\.?|₹|AED|Dhs?)`;
const AMOUNT_RE = new RegExp(`${CUR}\\s*${NUM}|${NUM}\\s*(INR|AED)\\b`, 'gi');

/** Words that, just before an amount, mean it is a balance or limit, not the transaction. */
const NOT_TXN_AMOUNT = /(bal(ance)?|limit|avl|available|outstanding|due)[\s.:a-z]{0,12}$/i;

const DEBIT_WORDS = /\b(debited|spent|withdrawn|paid|purchase|sent|deducted|debit)\b/i;
const CREDIT_WORDS = /\b(credited|received|deposited|refund(ed)?|reversed|cashback|credit)\b/i;

const STOP = String.raw`(?=\s+(?:on|via|ref|refno|txn|using|for|from|is|was|dated|avl|upi|not|\()\b|[.,;\n]|$)`;
const NAME = String.raw`([A-Za-z][A-Za-z0-9 &.'@_\-*/]{1,40}?)`;
const DEBIT_MERCHANT = [
  new RegExp(String.raw`\b(?:at|@)\s+${NAME}${STOP}`, 'i'),
  new RegExp(String.raw`\b(?:to|towards)\s+(?:VPA\s+)?${NAME}${STOP}`, 'i'),
  /\bInfo:?\s*([A-Za-z][^.\n]{1,40})/i,
];
const CREDIT_MERCHANT = [
  new RegExp(String.raw`\b(?:from|by)\s+(?:VPA\s+)?${NAME}${STOP}`, 'i'),
  /\bInfo:?\s*([A-Za-z][^.\n]{1,40})/i,
];
/** Captures that look like our own account, not a counterparty. */
const SELF_REF = /^(your|you|a\/c|ac|acct|account|card|xx)\b/i;

const ACCOUNT_RE = [
  /\b(?:a\/c|acct|account|ac|card)\b\.?\s*(?:no\.?\s*)?(?:ending\s*(?:with|in)?\s*)?[x*]*\s*(\d{3,6})\b/i,
  /\b[x*]{2,}(\d{3,6})\b/i,
];

const DATE_RE =
  /\b(\d{4}-\d{2}-\d{2}(?:[ T:]\d{1,2}:\d{2}(?::\d{2})?)?|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}(?:[ ,]+\d{1,2}:\d{2}(?::\d{2})?)?|\d{1,2}[-\s]?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-\s,]?\d{2,4})/i;

function findAmount(body: string, hints: ExtractHints): { minor: number; currency: Currency } | null {
  AMOUNT_RE.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = AMOUNT_RE.exec(body))) {
    const before = body.slice(Math.max(0, m.index - 20), m.index);
    if (NOT_TXN_AMOUNT.test(before)) continue;
    const currency = normalizeCurrency(m[1] ?? m[4] ?? '');
    const minor = parseAmountToMinor(m[2] ?? m[3] ?? '');
    if (currency && minor) return { minor, currency };
  }
  for (const re of hints.amount ?? []) {
    const hit = body.match(re);
    const minor = hit ? parseAmountToMinor(hit[1]) : null;
    if (minor) return { minor, currency: hints.defaultCurrency ?? 'INR' };
  }
  return null;
}

export function findDirection(body: string): Direction | null {
  const d = body.search(DEBIT_WORDS);
  const c = body.search(CREDIT_WORDS);
  if (d === -1 && c === -1) return null;
  if (c === -1) return 'debit';
  if (d === -1) return 'credit';
  // "debited from your a/c ... credited to SWIGGY" is a debit: the first verb wins.
  return d < c ? 'debit' : 'credit';
}

export function cleanMerchant(raw: string): string | null {
  const s = raw
    .replace(/\s+/g, ' ')
    .replace(/[\s.,;:\-*/]+$/, '')
    .trim();
  if (s.length < 2 || SELF_REF.test(s)) return null;
  return s;
}

export function findMerchant(body: string, direction: Direction, hints: ExtractHints = {}): string | null {
  const patterns = [...(hints.merchant ?? []), ...(direction === 'debit' ? DEBIT_MERCHANT : CREDIT_MERCHANT)];
  for (const re of patterns) {
    const hit = body.match(re);
    const name = hit ? cleanMerchant(hit[1]) : null;
    if (name) return name;
  }
  return null;
}

export function findAccount(body: string): string | null {
  for (const re of ACCOUNT_RE) {
    const hit = body.match(re);
    if (hit) return hit[1].slice(-4);
  }
  return null;
}

export function findDate(body: string): number | null {
  const hit = body.match(DATE_RE);
  return hit ? parseSmsDate(hit[1]) : null;
}

/** Keyword-and-pattern extraction shared by the generic parser and bank profiles. */
export function extract(body: string, parserId: string, hints: ExtractHints = {}): ParsedSms | null {
  const amount = findAmount(body, hints);
  const direction = findDirection(body);
  if (!amount || !direction) return null;
  return {
    amountMinor: amount.minor,
    currency: amount.currency,
    direction,
    merchant: findMerchant(body, direction, hints),
    accountHint: findAccount(body),
    occurredAt: findDate(body),
    parserId,
  };
}
