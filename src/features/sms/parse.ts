import { profileForSender } from './banks';
import { applyCustomParser, type CustomParser } from './custom-parser';
import { extract } from './extract';
import type { ParsedSms, RawSms } from './types';

/**
 * Tries, in order: the user's custom parsers (they were made for formats we got wrong),
 * the bank profile matching the sender, then the generic extractor.
 */
export function parseSms(sms: RawSms, customParsers: CustomParser[]): ParsedSms | null {
  for (const parser of customParsers) {
    const parsed = applyCustomParser(parser, sms);
    if (parsed) return parsed;
  }
  const profile = profileForSender(sms.sender);
  if (profile) {
    const parsed = extract(sms.body, `bank:${profile.id}`, profile);
    if (parsed) return parsed;
  }
  return extract(sms.body, 'generic');
}
