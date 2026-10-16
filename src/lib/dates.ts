const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

function fullYear(y: number): number {
  return y < 100 ? 2000 + y : y;
}

function build(y: number, m: number, d: number, hh = 0, mm = 0, ss = 0): number | null {
  if (m < 0 || m > 11 || d < 1 || d > 31) return null;
  const date = new Date(fullYear(y), m, d, hh, mm, ss);
  // Reject roll-overs like 31-02 becoming 3 March.
  if (date.getDate() !== d || date.getMonth() !== m) return null;
  return date.getTime();
}

/**
 * Parses the date formats Indian and UAE banks use in SMS. Day-first is assumed
 * for numeric dates (dd-mm-yy), which is what those banks send.
 * Returns epoch ms in local time, or null.
 */
export function parseSmsDate(raw: string): number | null {
  const s = raw.trim();
  let m: RegExpMatchArray | null;

  // 2026-10-11 or 2026-10-11 14:05(:30)
  if ((m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?/))) {
    return build(+m[1], +m[2] - 1, +m[3], +(m[4] ?? 0), +(m[5] ?? 0), +(m[6] ?? 0));
  }
  // 11-10-26, 11/10/2026, 11.10.2026 with optional time
  if ((m = s.match(/^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})(?:[ ,]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?/))) {
    return build(+m[3], +m[2] - 1, +m[1], +(m[4] ?? 0), +(m[5] ?? 0), +(m[6] ?? 0));
  }
  // 11-Oct-26, 11 Oct 2026, 11Oct26
  if ((m = s.match(/^(\d{1,2})[-\s]?([A-Za-z]{3})[A-Za-z]*[-\s,]?(\d{2,4})(?:[ ,]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?/))) {
    const month = MONTHS.indexOf(m[2].toLowerCase());
    if (month === -1) return null;
    return build(+m[3], month, +m[1], +(m[4] ?? 0), +(m[5] ?? 0), +(m[6] ?? 0));
  }
  return null;
}

export function startOfMonth(ts: number): number {
  const d = new Date(ts);
  return new Date(d.getFullYear(), d.getMonth(), 1).getTime();
}

export function addMonths(ts: number, n: number): number {
  const d = new Date(ts);
  return new Date(d.getFullYear(), d.getMonth() + n, 1).getTime();
}

export function formatMonth(ts: number): string {
  return new Date(ts).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

export function formatDate(ts: number): string {
  return new Date(ts).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function formatDateTime(ts: number): string {
  return new Date(ts).toLocaleString('en-GB', {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** "2026-10-11" for date inputs. */
export function toDateInput(ts: number): string {
  const d = new Date(ts);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
