package com.najeemfasil.expense.feature.sms

/**
 * Tries, in order: the user's custom parsers (they were made for formats we got wrong),
 * the bank profile matching the sender, then the generic extractor.
 */
fun parseSms(sms: RawSms, customParsers: List<CustomParser>): ParsedSms? {
    for (parser in customParsers) applyCustomParser(parser, sms)?.let { return it }
    val profile = profileForSender(sms.sender)
    if (profile != null) extract(sms.body, "bank:${profile.id}", profile)?.let { return it }
    return extract(sms.body, "generic")
}
