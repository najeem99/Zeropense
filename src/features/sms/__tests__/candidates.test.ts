import { findAmountCandidates, findDigitsCandidates } from '../candidates';
import { applyCustomParser, buildPattern } from '../custom-parser';

const enbd =
  'Purchase of AED 45.50 with Credit Card ending 1234 at CARREFOUR, DUBAI. Avl Cr. Limit is AED 12,000.00';

describe('findAmountCandidates', () => {
  it('lists every amount with its currency and flags the balance', () => {
    const c = findAmountCandidates(enbd);
    expect(c.map((a) => [a.minor, a.currency, a.looksLikeBalance])).toEqual([
      [4550, 'AED', false],
      [1200000, 'AED', true],
    ]);
    expect(enbd.slice(c[0].start, c[0].end)).toBe('45.50');
    expect(c[0].context).toContain('Purchase of AED 45.50');
  });

  it('includes bare decimals when the bank omits the currency', () => {
    const c = findAmountCandidates('A/C X1234 debited by 150.0 on date 11Oct26 trf to ZOMATO Refno 612345678901');
    expect(c).toHaveLength(1);
    expect(c[0]).toMatchObject({ minor: 15000, currency: null });
  });

  it('ignores reference numbers and dates', () => {
    expect(findAmountCandidates('Ref 612345678901 on 11-10-26')).toEqual([]);
  });
});

describe('findDigitsCandidates', () => {
  it.each([
    ['Credit Card ending 1234 at X', '1234'],
    ['Acct XX123 debited', '123'],
    ['From HDFC Bank A/C *5678', '5678'],
    ['A/c no. XX4321 debited', '4321'],
  ])('finds the digits in %s', (body, digits) => {
    expect(findDigitsCandidates(body).map((d) => d.digits)).toContain(digits);
  });
});

describe('SMS configuration flow', () => {
  it('builds a parser from the chosen amount and card, skipping the balance', () => {
    const amount = findAmountCandidates(enbd)[0];
    const card = findDigitsCandidates(enbd)[0];
    const built = buildPattern(enbd, [
      { field: 'amount', start: amount.start, end: amount.end },
      { field: 'account', start: card.start, end: card.end },
    ]);
    expect(built.ok).toBe(true);
    if (!built.ok) return;
    const parser = {
      id: 'p',
      name: 'ENBD',
      senderContains: null,
      pattern: built.pattern,
      fields: built.fields,
      currency: 'AED' as const,
      direction: 'debit' as const,
    };
    const next = 'Purchase of AED 7.00 with Credit Card ending 9876 at CARREFOUR, DUBAI. Avl Cr. Limit is AED 999.00';
    expect(applyCustomParser(parser, { sender: 'ENBD', body: next, receivedAt: 0 })).toMatchObject({
      amountMinor: 700,
      accountHint: '9876',
    });
  });
});
