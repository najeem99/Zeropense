import { applyCustomParser, buildPattern, type CustomParser, type FieldMark } from '../custom-parser';

const sample = 'Dear Customer, AED 87.25 spent at LULU HYPERMARKET on 03-10-2026 using card 4455. Avl bal AED 1,204.10';

function mark(field: FieldMark['field'], text: string): FieldMark {
  const start = sample.indexOf(text);
  return { field, start, end: start + text.length };
}

function makeParser(marks: FieldMark[]): CustomParser {
  const built = buildPattern(sample, marks);
  if (!built.ok) throw new Error(built.error);
  return {
    id: 'p1',
    name: 'My bank',
    senderContains: 'mybank',
    pattern: built.pattern,
    fields: built.fields,
    currency: 'AED',
    direction: 'debit',
  };
}

const marks = [mark('amount', '87.25'), mark('merchant', 'LULU HYPERMARKET'), mark('date', '03-10-2026'), mark('account', '4455')];

describe('custom parser', () => {
  it('reads back the sample it was built from', () => {
    const r = applyCustomParser(makeParser(marks), { sender: 'MyBank', body: sample, receivedAt: 0 });
    expect(r).toMatchObject({
      amountMinor: 8725,
      currency: 'AED',
      direction: 'debit',
      merchant: 'LULU HYPERMARKET',
      accountHint: '4455',
      parserId: 'custom:p1',
    });
    expect(new Date(r!.occurredAt!).getDate()).toBe(3);
  });

  it('matches a new message with different values and digit lengths', () => {
    const body = 'Dear Customer, AED 1,250.00 spent at NOON on 14-10-2026 using card 4455. Avl bal AED 99.00';
    const r = applyCustomParser(makeParser(marks), { sender: 'MyBank', body, receivedAt: 0 });
    expect(r).toMatchObject({ amountMinor: 125000, merchant: 'NOON' });
  });

  it('ignores other senders and other formats', () => {
    const parser = makeParser(marks);
    expect(applyCustomParser(parser, { sender: 'Other', body: sample, receivedAt: 0 })).toBeNull();
    expect(applyCustomParser(parser, { sender: 'MyBank', body: 'Your OTP is 1234', receivedAt: 0 })).toBeNull();
  });

  it('escapes regex characters in the literal text', () => {
    const s = 'Txn (POS) of $ 10.00 at A+B Store?';
    const start = s.indexOf('10.00');
    const built = buildPattern(s, [{ field: 'amount', start, end: start + 5 }]);
    expect(built.ok).toBe(true);
    if (built.ok) expect(new RegExp(`^${built.pattern}$`, 's').test(s)).toBe(true);
  });

  it('rejects missing amount and overlapping marks', () => {
    expect(buildPattern(sample, [mark('merchant', 'LULU')])).toEqual({ ok: false, error: 'Mark the amount.' });
    const overlap = buildPattern(sample, [mark('amount', '87.25'), { field: 'merchant', start: marks[0].start + 1, end: marks[0].end + 3 }]);
    expect(overlap.ok).toBe(false);
  });
});
