package com.najeemfasil.expense.feature.categories

import com.najeemfasil.expense.core.jsRegex
import java.util.Locale

data class MerchantRule(val merchantKey: String, val categoryId: String)

/**
 * Money back from a shop. Stored as money in, but it reduces spending instead of counting as
 * income, and it is never learned as the shop's category (the next purchase there is still spending).
 */
const val REFUND_CATEGORY = "refund"

private val NOISE_WORDS = setOf(
    "PVT", "PRIVATE", "LTD", "LIMITED", "LLC", "INC", "CO", "FZ", "FZE", "FZCO", "FZLLC",
    "INDIA", "IN", "UAE", "AE", "DUBAI", "ABU", "DHABI", "SHARJAH", "UPI", "POS", "THE"
)

private val AFTER_AT = jsRegex("@.*$")
private val NOT_LETTERS = Regex("[^A-Z]+")

/**
 * Normalises a merchant name so variants map to one rule:
 * "swiggy@ybl", "SWIGGY  PVT LTD" → "SWIGGY". Digits are dropped too (kept for compatibility
 * with rules already stored).
 */
fun merchantKey(merchant: String): String = merchant
    .uppercase(Locale.ROOT)
    .replaceFirst(AFTER_AT, "")
    .replace(NOT_LETTERS, " ")
    .split(' ')
    .filter { it.length > 1 && it !in NOISE_WORDS }
    .joinToString(" ")

/**
 * Exact key match first; otherwise the longest learned key that appears as whole
 * words in this merchant ("GAZEBO" matches "GAZEBO KARAMA").
 */
fun suggestCategory(merchant: String?, rules: List<MerchantRule>): String? {
    if (merchant.isNullOrEmpty()) return null
    val key = merchantKey(merchant)
    if (key.isEmpty()) return null
    var best: MerchantRule? = null
    for (rule in rules) {
        if (rule.merchantKey == key) return rule.categoryId
        if (rule.merchantKey.length < 3) continue
        val contained = " $key ".contains(" ${rule.merchantKey} ")
        if (contained && (best == null || rule.merchantKey.length > best.merchantKey.length)) best = rule
    }
    return best?.categoryId
}
