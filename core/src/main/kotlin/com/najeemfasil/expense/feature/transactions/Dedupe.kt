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
