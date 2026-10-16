import { merchantKey, suggestCategory } from '@/features/categories/categorizer';
import { summarize } from '@/features/dashboard/summary';
import { findDuplicate } from '@/features/transactions/dedupe';
import { parseSmsDate } from '@/lib/dates';
import { formatMoney, parseAmountToMinor } from '@/lib/money';

describe('money', () => {
  it.each([
    ['1,23,456.78', 12345678],
    ['250', 25000],
    ['10.5', 1050],
    ['0', null],
    ['abc', null],
  ])('parses %s', (raw, minor) => expect(parseAmountToMinor(raw)).toBe(minor));

  it('formats per currency', () => {
    expect(formatMoney(12345678, 'INR')).toBe('₹1,23,456.78');
    expect(formatMoney(-4550, 'AED')).toBe('−AED 45.50');
  });
});

describe('parseSmsDate', () => {
  const ymd = (ts: number | null) => (ts === null ? null : new Date(ts).toISOString().slice(0, 10));
  it.each([
    ['11-10-26', 2026, 9, 11],
    ['11/10/2026', 2026, 9, 11],
    ['11-Oct-26', 2026, 9, 11],
    ['11Oct26', 2026, 9, 11],
    ['2026-10-11', 2026, 9, 11],
  ])('parses %s', (raw, y, m, d) => {
    expect(ymd(parseSmsDate(raw))).toBe(ymd(new Date(y, m, d).getTime()));
  });
  it('rejects impossible dates', () => expect(parseSmsDate('31-02-26')).toBeNull());
});

describe('categorizer', () => {
  it('normalises merchant variants', () => {
    expect(merchantKey('swiggy@ybl')).toBe('SWIGGY');
    expect(merchantKey('SWIGGY  PVT LTD')).toBe('SWIGGY');
    expect(merchantKey('Gazebo Restaurant LLC, Dubai')).toBe('GAZEBO RESTAURANT');
  });

  it('remembers learned merchants, including longer variants', () => {
    const rules = [
      { merchantKey: 'GAZEBO', categoryId: 'restaurant' },
      { merchantKey: 'AMAZON', categoryId: 'shopping' },
      { merchantKey: 'AMAZON PRIME', categoryId: 'subscriptions' },
    ];
    expect(suggestCategory('Gazebo', rules)).toBe('restaurant');
    expect(suggestCategory('GAZEBO KARAMA', rules)).toBe('restaurant');
    expect(suggestCategory('Amazon Prime Video', rules)).toBe('subscriptions');
    expect(suggestCategory('Unknown Shop', rules)).toBeNull();
    expect(suggestCategory(null, rules)).toBeNull();
  });
});

describe('findDuplicate', () => {
  const base = { amountMinor: 1000, currency: 'AED' as const, direction: 'debit' as const, occurredAt: 0, accountId: 'a' };
  it('matches the closest within the window', () => {
    expect(findDuplicate(base, [{ ...base, id: 'far', occurredAt: 10 * 60_000 }, { ...base, id: 'near', occurredAt: 60_000 }])).toBe('near');
  });
  it('ignores different amounts, accounts and old transactions', () => {
    expect(findDuplicate(base, [{ ...base, id: 'x', amountMinor: 1001 }])).toBeNull();
    expect(findDuplicate(base, [{ ...base, id: 'x', accountId: 'b' }])).toBeNull();
    expect(findDuplicate(base, [{ ...base, id: 'x', occurredAt: 60 * 60_000 }])).toBeNull();
  });
});

describe('summarize', () => {
  it('keeps currencies separate and ranks spending', () => {
    const s = summarize([
      { amountMinor: 100000, currency: 'AED', direction: 'credit', categoryId: null },
      { amountMinor: 3000, currency: 'AED', direction: 'debit', categoryId: 'food' },
      { amountMinor: 5000, currency: 'AED', direction: 'debit', categoryId: 'rent' },
      { amountMinor: 2000, currency: 'AED', direction: 'debit', categoryId: 'food' },
      { amountMinor: 70000, currency: 'INR', direction: 'debit', categoryId: null },
    ]);
    expect(s).toEqual([
      {
        currency: 'AED',
        inMinor: 100000,
        outMinor: 10000,
        netMinor: 90000,
        topSpending: [
          { categoryId: 'food', minor: 5000 },
          { categoryId: 'rent', minor: 5000 },
        ],
      },
      { currency: 'INR', inMinor: 0, outMinor: 70000, netMinor: -70000, topSpending: [{ categoryId: null, minor: 70000 }] },
    ]);
  });
});
