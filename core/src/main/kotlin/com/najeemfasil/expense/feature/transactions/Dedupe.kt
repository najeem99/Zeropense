package com.najeemfasil.expense.feature.transactions

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction

data class DedupeCandidate(
    val id: String,
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val occurredAt: Long,
    val accountId: String?
)

/** Banks and UPI apps often both send an SMS for one payment, minutes apart. */
const val DUPLICATE_WINDOW_MS = 15 * 60 * 1000L

/**
 * Returns the id of an existing transaction this one probably duplicates.
 * Two genuine identical payments look the same, so a match is only a flag for the
 * review queue, never an automatic delete. The candidate's own id is ignored.
 */
fun findDuplicate(candidate: DedupeCandidate, existing: List<DedupeCandidate>, windowMs: Long = DUPLICATE_WINDOW_MS): String? {
    var bestId: String? = null
    var bestGap = Long.MAX_VALUE
    for (e in existing) {
        if (e.amountMinor != candidate.amountMinor) continue
        if (e.currency != candidate.currency || e.direction != candidate.direction) continue
        if (!e.accountId.isNullOrEmpty() && !candidate.accountId.isNullOrEmpty() && e.accountId != candidate.accountId) continue
        val gap = kotlin.math.abs(e.occurredAt - candidate.occurredAt)
        if (gap <= windowMs && (bestId == null || gap < bestGap)) {
            bestId = e.id
            bestGap = gap
        }
    }
    return bestId
}

/**
 * Candidates bucketed by amount, currency and direction, so checking one SMS against
 * thousands of transactions doesn't scan them all. Add to it as new drafts are created.
 */
class DedupeIndex(items: List<DedupeCandidate> = emptyList()) {
    private val buckets = HashMap<String, MutableList<DedupeCandidate>>()

    init {
        items.forEach(::add)
    }

    private fun key(c: DedupeCandidate) = "${c.amountMinor}|${c.currency.name}|${c.direction.db}"

    fun add(item: DedupeCandidate) {
        buckets.getOrPut(key(item)) { ArrayList() }.add(item)
    }

    fun find(candidate: DedupeCandidate, windowMs: Long = DUPLICATE_WINDOW_MS): String? = findDuplicate(candidate, buckets[key(candidate)].orEmpty(), windowMs)
}
