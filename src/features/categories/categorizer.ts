export type MerchantRule = { merchantKey: string; categoryId: string };

/**
 * Money back from a shop. Stored as money in, but it reduces spending instead of counting as
 * income, and it is never learned as the shop's category (the next purchase there is still spending).
 */
export const REFUND_CATEGORY = 'refund';

const NOISE_WORDS = new Set([
  'PVT', 'PRIVATE', 'LTD', 'LIMITED', 'LLC', 'INC', 'CO', 'FZ', 'FZE', 'FZCO', 'FZLLC',
  'INDIA', 'IN', 'UAE', 'AE', 'DUBAI', 'ABU', 'DHABI', 'SHARJAH', 'UPI', 'POS', 'THE',
]);

/**
 * Normalises a merchant name so variants map to one rule:
 * "swiggy@ybl", "SWIGGY  PVT LTD", "Swiggy*Order 123" → "SWIGGY".
 */
export function merchantKey(merchant: string): string {
  return merchant
    .toUpperCase()
    .replace(/@.*$/, '')
    .replace(/[^A-Z]+/g, ' ')
    .split(' ')
    .filter((w) => w.length > 1 && !NOISE_WORDS.has(w))
    .join(' ');
}

/**
 * Exact key match first; otherwise the longest learned key that appears as whole
 * words in this merchant ("GAZEBO" matches "GAZEBO KARAMA").
 */
export function suggestCategory(merchant: string | null, rules: MerchantRule[]): string | null {
  if (!merchant) return null;
  const key = merchantKey(merchant);
  if (!key) return null;
  let best: MerchantRule | null = null;
  for (const rule of rules) {
    if (rule.merchantKey === key) return rule.categoryId;
    if (rule.merchantKey.length < 3) continue;
    const contained = ` ${key} `.includes(` ${rule.merchantKey} `);
    if (contained && (!best || rule.merchantKey.length > best.merchantKey.length)) best = rule;
  }
  return best?.categoryId ?? null;
}
