const RULES: { reason: string; test: RegExp }[] = [
  { reason: 'OTP / verification code', test: /\b(otp|one[\s-]time password|verification code|security code)\b/i },
  { reason: 'Payment request, not a payment', test: /\b(has requested|collect request|requested money|payment request)\b/i },
  { reason: 'Upcoming debit reminder', test: /\b(will be debited|is due|due on|due date|auto[\s-]?debit.{0,30}scheduled)\b/i },
  { reason: 'Promotional', test: /\b(pre[\s-]?approved|offer|apply now|click here to avail|cashback up to|win)\b/i },
  { reason: 'Failed or declined', test: /\b(declined|failed|unsuccessful|could not be processed)\b/i },
];

/** Banks send from alphanumeric IDs or short codes; long phone numbers are people. */
export const PERSONAL_SENDER = /^\+?\d{8,}$/;

/** Returns why an SMS should be skipped, or null if it may be a real transaction. */
export function skipReason(body: string, sender = ''): string | null {
  if (PERSONAL_SENDER.test(sender.replace(/[\s-]/g, ''))) return 'Personal message';
  for (const rule of RULES) {
    if (rule.test.test(body)) return rule.reason;
  }
  return null;
}
