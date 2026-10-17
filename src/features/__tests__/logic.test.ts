import { merchantKey, suggestCategory } from '@/features/categories/categorizer';
import { categoryLook } from '@/features/categories/appearance';
import { dailySpending } from '@/features/dashboard/daily';
import { buildSlices, layoutArcs } from '@/features/dashboard/spending-donut';
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
        refundMinor: 0,
        netMinor: 90000,
        topSpending: [
          { categoryId: 'food', minor: 5000 },
          { categoryId: 'rent', minor: 5000 },
        ],
      },
      { currency: 'INR', inMinor: 0, outMinor: 70000, refundMinor: 0, netMinor: -70000, topSpending: [{ categoryId: null, minor: 70000 }] },
    ]);
  });
});

describe('refunds', () => {
  it('lower spending instead of counting as income', () => {
    const [s] = summarize([
      { amountMinor: 1200, currency: 'AED', direction: 'debit', categoryId: 'shopping' },
      { amountMinor: 1200, currency: 'AED', direction: 'credit', categoryId: 'refund' },
      { amountMinor: 50000, currency: 'AED', direction: 'credit', categoryId: 'salary' },
    ]);
    expect(s).toMatchObject({ inMinor: 50000, outMinor: 0, refundMinor: 1200, netMinor: 50000 });
  });
});

describe('spending donut', () => {
  const nameOf = (id: string | null) => id ?? 'Uncategorised';

  it('keeps five slices and folds the rest into a grey Other', () => {
    const spending = ['groceries', 'restaurant', 'fuel', 'rent', 'shopping', 'travel', 'health'].map((id, i) => ({
      categoryId: id,
      minor: (10 - i) * 100,
    }));
    const slices = buildSlices(spending, nameOf, 'light');
    expect(slices.map((s) => s.label)).toEqual(['groceries', 'restaurant', 'fuel', 'rent', 'Other (3)']);
    expect(slices[4].minor).toBe(600 + 500 + 400);
    expect(slices[0].color).toBe(categoryLook('groceries', 'light').color);
  });

  it('lays the arcs end to end around the ring', () => {
    const slices = buildSlices([{ categoryId: 'rent', minor: 300 }, { categoryId: 'fuel', minor: 100 }], nameOf, 'dark');
    const arcs = layoutArcs(slices, 400);
    expect(arcs.map((a) => [a.offset, a.dash])).toEqual([
      [0, 298],
      [300, 98],
    ]);
  });
});

describe('dailySpending', () => {
  const oct = (d: number, h = 12) => new Date(2026, 9, d, h).getTime();
  const tx = (d: number, minor: number, extra = {}) => ({
    amountMinor: minor,
    currency: 'AED' as const,
    direction: 'debit' as const,
    categoryId: null,
    occurredAt: oct(d),
    ...extra,
  });

  it('buckets money out by day and averages over the days so far', () => {
    const r = dailySpending(
      [
        tx(1, 100),
        tx(1, 50, { occurredAt: oct(1, 23) }),
        tx(3, 300),
        tx(2, 999, { direction: 'credit' }),
        tx(2, 70, { currency: 'INR' }),
      ],
      'AED',
      oct(1, 0),
      31,
      oct(3),
    );
    expect(r.days.slice(0, 4)).toEqual([150, 0, 300, 0]);
    expect(r.days).toHaveLength(31);
    expect(r.averageMinor).toBe(150); // 450 over 3 days
    expect(r.peak).toEqual({ index: 2, minor: 300 });
  });

  it('has no peak without spending', () => {
    expect(dailySpending([], 'AED', oct(1, 0), 31, oct(31)).peak).toBeNull();
  });
});
