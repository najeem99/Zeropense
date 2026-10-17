import { normalizeCurrency, parseAmountToMinor, type Currency } from '@/lib/money';

/** One number in an SMS that could be the transaction amount. `start`/`end` cover the digits only. */
export type AmountCandidate = {
  start: number;
  end: number;
  minor: number;
  currency: Currency | null;
  /** Text around the amount, so the user can tell "spent AED 45" from "Avl bal AED 1,200". */
  context: string;
  /** Preceded by balance/limit wording — shown, but not suggested. */
  looksLikeBalance: boolean;
};

export type DigitsCandidate = { start: number; end: number; digits: string };

const WITH_CURRENCY = /(INR|Rs\.?|₹|AED|Dhs?)\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*(INR|AED)\b/gi;
/** Bare decimals like SBI's "debited by 150.0". Whole numbers are too often refs or dates. */
const BARE_DECIMAL = /(?<![\d.,/-])(\d[\d,]*\.\d{1,2})(?![\d/-])/g;
const BALANCE_BEFORE = /(bal(ance)?|limit|avl|available|outstanding|due)[\s.:a-z]{0,12}$/i;

function contextAround(body: string, start: number, end: number): string {
  const from = Math.max(0, start - 24);
  const to = Math.min(body.length, end + 16);
  return `${from > 0 ? '…' : ''}${body.slice(from, to).replace(/\s+/g, ' ').trim()}${to < body.length ? '…' : ''}`;
}

/** Every amount in the message, in order of appearance. */
export function findAmountCandidates(body: string): AmountCandidate[] {
  const out: AmountCandidate[] = [];
  const taken = (s: number) => out.some((c) => s >= c.start && s < c.end);

  for (const m of body.matchAll(WITH_CURRENCY)) {
    const digits = m[2] ?? m[3];
    const start = m[2] ? m.index + m[0].length - digits.length : m.index;
    const end = start + digits.length;
    const minor = parseAmountToMinor(digits);
    if (!minor) continue;
    out.push({
      start,
      end,
      minor,
      currency: normalizeCurrency(m[1] ?? m[4]),
      context: contextAround(body, m.index, m.index + m[0].length),
      looksLikeBalance: BALANCE_BEFORE.test(body.slice(Math.max(0, m.index - 20), m.index)),
    });
  }
  for (const m of body.matchAll(BARE_DECIMAL)) {
    const start = m.index;
    const end = start + m[1].length;
    const minor = parseAmountToMinor(m[1]);
    if (!minor || taken(start)) continue;
    out.push({
      start,
      end,
      minor,
      currency: null,
      context: contextAround(body, start, end),
      looksLikeBalance: BALANCE_BEFORE.test(body.slice(Math.max(0, start - 20), start)),
    });
  }
  return out.sort((a, b) => a.start - b.start);
}

const DIGITS_AFTER = /(?:[x*]+|ending\s*(?:with|in)?\s*[:\-]?\s*|(?:card|a\/c|acct|account|ac)\.?\s*(?:no\.?\s*)?)(\d{3,6})\b/gi;

/** Numbers that look like a masked card/account ("XX1234", "card ending 1234", "A/C *5678"). */
export function findDigitsCandidates(body: string): DigitsCandidate[] {
  const out: DigitsCandidate[] = [];
  for (const m of body.matchAll(DIGITS_AFTER)) {
    const end = m.index + m[0].length;
    const start = end - m[1].length;
    if (!out.some((c) => c.start === start)) out.push({ start, end, digits: m[1] });
  }
  return out;
}
