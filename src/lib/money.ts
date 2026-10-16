export const CURRENCIES = ['AED', 'INR'] as const;
export type Currency = (typeof CURRENCIES)[number];

export type Direction = 'debit' | 'credit';

const SYMBOLS: Record<Currency, string> = { AED: 'AED', INR: '₹' };
const LOCALES: Record<Currency, string> = { AED: 'en-AE', INR: 'en-IN' };

/** Maps the many ways banks write a currency ("Rs.", "₹", "INR", "AED") to a Currency. */
export function normalizeCurrency(token: string): Currency | null {
  const t = token.trim().toUpperCase().replace(/\.$/, '');
  if (t === 'INR' || t === 'RS' || t === '₹') return 'INR';
  if (t === 'AED' || t === 'DHS' || t === 'DH') return 'AED';
  return null;
}

/**
 * Parses an amount string as written in SMS ("1,23,456.78", "250", "1,000.5")
 * into minor units (paise / fils). Returns null when it isn't a positive number.
 */
export function parseAmountToMinor(raw: string): number | null {
  const cleaned = raw.replace(/[,\s]/g, '');
  if (!/^\d+(\.\d+)?$/.test(cleaned)) return null;
  const [whole, frac = ''] = cleaned.split('.');
  const minor = Number(whole) * 100 + Number((frac + '00').slice(0, 2));
  return minor > 0 ? minor : null;
}

export function formatMoney(minor: number, currency: Currency, opts: { signed?: boolean } = {}): string {
  const abs = Math.abs(minor) / 100;
  const body = abs.toLocaleString(LOCALES[currency], {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const sign = minor < 0 ? '−' : opts.signed && minor > 0 ? '+' : '';
  const symbol = SYMBOLS[currency];
  return currency === 'INR' ? `${sign}${symbol}${body}` : `${sign}${symbol} ${body}`;
}

/** For editing: minor units back to a plain "1234.50" string. */
export function minorToInput(minor: number): string {
  return (minor / 100).toFixed(2);
}
