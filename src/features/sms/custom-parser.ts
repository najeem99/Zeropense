import { parseSmsDate } from '@/lib/dates';
import { parseAmountToMinor, type Currency, type Direction } from '@/lib/money';

import { cleanMerchant } from './extract';
import type { ParsedSms, RawSms } from './types';

export const FIELD_NAMES = ['amount', 'merchant', 'date', 'account'] as const;
export type FieldName = (typeof FIELD_NAMES)[number];

/** A span of the sample SMS the user marked as a field. `end` is exclusive. */
export type FieldMark = { field: FieldName; start: number; end: number };

export type CustomParser = {
  id: string;
  name: string;
  /** Optional case-insensitive substring the sender must contain, e.g. "ENBD". */
  senderContains: string | null;
  /** Regex source built by buildPattern. */
  pattern: string;
  /** Which field each capture group holds, in order. */
  fields: FieldName[];
  currency: Currency;
  direction: Direction;
};

const CAPTURE: Record<FieldName, string> = {
  amount: String.raw`(\d[\d,]*(?:\.\d+)?)`,
  merchant: '(.+?)',
  date: '(.+?)',
  account: String.raw`([A-Za-z*]*\d+)`,
};

/**
 * Turns the text around marked fields into a pattern that tolerates the parts that
 * change between messages: any number ("99.00", "1,204.10", "2026") matches any number,
 * any whitespace any whitespace.
 */
function generalizeLiteral(text: string): string {
  let out = '';
  for (const token of text.match(/\d[\d,]*(?:\.\d+)?|\s+|[^\d\s]/g) ?? []) {
    if (/^\d/.test(token)) out += String.raw`\d[\d,]*(?:\.\d+)?`;
    else if (/^\s/.test(token)) out += String.raw`\s+`;
    else out += token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
  return out;
}

export type BuildResult = { ok: true; pattern: string; fields: FieldName[] } | { ok: false; error: string };

export function buildPattern(sample: string, marks: FieldMark[]): BuildResult {
  const sorted = [...marks].sort((a, b) => a.start - b.start);
  if (!sorted.some((m) => m.field === 'amount')) return { ok: false, error: 'Mark the amount.' };
  const seen = new Set<FieldName>();
  for (let i = 0; i < sorted.length; i++) {
    const m = sorted[i];
    if (m.start < 0 || m.end > sample.length || m.end <= m.start) return { ok: false, error: 'A marked span is empty.' };
    if (seen.has(m.field)) return { ok: false, error: `${m.field} is marked twice.` };
    seen.add(m.field);
    if (i > 0 && m.start < sorted[i - 1].end) return { ok: false, error: 'Marked spans overlap.' };
  }

  let pattern = '';
  let cursor = 0;
  for (const m of sorted) {
    pattern += generalizeLiteral(sample.slice(cursor, m.start)) + CAPTURE[m.field];
    cursor = m.end;
  }
  pattern += generalizeLiteral(sample.slice(cursor));
  return { ok: true, pattern, fields: sorted.map((m) => m.field) };
}

export function applyCustomParser(parser: CustomParser, sms: RawSms): ParsedSms | null {
  if (parser.senderContains && !sms.sender.toLowerCase().includes(parser.senderContains.toLowerCase())) {
    return null;
  }
  let re: RegExp;
  try {
    re = new RegExp(String.raw`^\s*` + parser.pattern + String.raw`\s*$`, 'is');
  } catch {
    return null;
  }
  const match = sms.body.match(re);
  if (!match) return null;

  const value = (field: FieldName) => {
    const i = parser.fields.indexOf(field);
    return i === -1 ? null : (match[i + 1] ?? null);
  };
  const amountMinor = parseAmountToMinor(value('amount') ?? '');
  if (!amountMinor) return null;
  const merchant = value('merchant');
  const date = value('date');
  const account = value('account');

  return {
    amountMinor,
    currency: parser.currency,
    direction: parser.direction,
    merchant: merchant ? cleanMerchant(merchant) : null,
    accountHint: account ? account.replace(/\D/g, '').slice(-4) || null : null,
    occurredAt: date ? parseSmsDate(date) : null,
    parserId: `custom:${parser.id}`,
  };
}
