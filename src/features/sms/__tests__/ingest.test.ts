import { matchAccount, processSms, type IngestContext } from '../ingest';

const t0 = new Date(2026, 9, 11, 13, 0).getTime();

const ctx = (over: Partial<IngestContext> = {}): IngestContext => ({
  customParsers: [],
  accounts: [
    { id: 'hdfc', currency: 'INR', smsHint: '5678' },
    { id: 'enbd', currency: 'AED', smsHint: '1234' },
  ],
  recent: [],
  rules: [{ merchantKey: 'SWIGGY', categoryId: 'food' }],
  ...over,
});

describe('processSms', () => {
  const body = 'Sent Rs.500.00\nFrom HDFC Bank A/C *5678\nTo SWIGGY\nRef 123';

  it('builds a draft with account, learned category and SMS time', () => {
    const r = processSms({ sender: 'VM-HDFCBK', body, receivedAt: t0 }, ctx());
    expect(r.kind).toBe('draft');
    if (r.kind !== 'draft') return;
    expect(r.draft).toMatchObject({
      amountMinor: 50000,
      accountId: 'hdfc',
      categoryId: 'food',
      occurredAt: t0,
      duplicateOfId: null,
    });
  });

  it('flags a second SMS for the same payment as a duplicate', () => {
    const recent = [{ id: 'tx1', amountMinor: 50000, currency: 'INR' as const, direction: 'debit' as const, occurredAt: t0 - 2 * 60_000, accountId: 'hdfc' }];
    const r = processSms({ sender: 'VM-HDFCBK', body, receivedAt: t0 }, ctx({ recent }));
    expect(r.kind === 'draft' && r.draft.duplicateOfId).toBe('tx1');
  });

  it('skips OTP messages before parsing', () => {
    const r = processSms({ sender: 'VM-HDFCBK', body: '482913 is your OTP for Rs 500 at SWIGGY', receivedAt: t0 }, ctx());
    expect(r).toEqual({ kind: 'skipped', reason: 'OTP / verification code' });
  });

  it('reports unparsed messages, noting whether they had an amount', () => {
    expect(processSms({ sender: 'X', body: 'Hello there', receivedAt: t0 }, ctx())).toEqual({
      kind: 'unparsed',
      looksFinancial: false,
    });
    expect(processSms({ sender: 'X', body: 'Your bill of AED 99.00 is ready', receivedAt: t0 }, ctx())).toEqual({
      kind: 'unparsed',
      looksFinancial: true,
    });
  });

  it('skips messages from personal phone numbers', () => {
    const r = processSms({ sender: '+971 50 123 4567', body: 'Sent you AED 50 for lunch, paid at GAZEBO', receivedAt: t0 }, ctx());
    expect(r).toEqual({ kind: 'skipped', reason: 'Personal message' });
  });

  it('keeps the digits of a card that has not been added yet', () => {
    const body = 'Purchase of AED 45.50 with Credit Card ending 9999 at CARREFOUR, DUBAI.';
    const r = processSms({ sender: 'EmiratesNBD', body, receivedAt: t0 }, ctx());
    expect(r.kind === 'draft' && r.draft).toMatchObject({ accountId: null, accountHint: '9999' });
  });
});

describe('matchAccount', () => {
  const accounts = ctx().accounts;
  it('uses last digits first', () => expect(matchAccount('1234', 'INR', accounts)).toBe('enbd'));
  it('leaves unknown digits unlinked instead of guessing', () => expect(matchAccount('9999', 'AED', accounts)).toBeNull());
  it('falls back to the only account in that currency', () => expect(matchAccount(null, 'AED', accounts)).toBe('enbd'));
  it('gives up when ambiguous', () =>
    expect(matchAccount(null, 'INR', [...accounts, { id: 'sbi', currency: 'INR', smsHint: null }])).toBeNull());
});
