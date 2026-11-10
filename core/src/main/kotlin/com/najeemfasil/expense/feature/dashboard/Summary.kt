package com.najeemfasil.expense.feature.dashboard

import com.najeemfasil.expense.core.Currency
import com.najeemfasil.expense.core.Direction

/** The amount fields of one accepted transaction, for totals and charts. */
data class SummaryInput(
    val amountMinor: Long,
    val currency: Currency,
    val direction: Direction,
    val categoryId: String?,
    val occurredAt: Long = 0
)
