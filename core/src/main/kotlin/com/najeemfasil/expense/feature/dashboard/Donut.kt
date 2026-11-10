package com.najeemfasil.expense.feature.dashboard

import com.najeemfasil.expense.feature.categories.categoryLook
import com.najeemfasil.expense.feature.categories.otherColor

/** Beyond this many categories the rest fold into one grey "Other" slice. */
private const val MAX_SLICES = 5

/** Surface gap between slices, in px along the ring. */
private const val GAP = 2

data class Slice(
    val key: String,
    val categoryId: String?,
    val label: String,
    val icon: String,
    /** 0xRRGGBB */
    val color: Int,
    val minor: Long
)

fun buildSlices(spending: List<CategorySpending>, nameOf: (String?) -> String, dark: Boolean): List<Slice> {
    val sorted = spending.sortedByDescending { it.minor }
    val head = if (sorted.size > MAX_SLICES) sorted.take(MAX_SLICES - 1) else sorted
    val rest = sorted.drop(head.size)
    val slices = head.mapTo(ArrayList()) { s ->
        val look = categoryLook(s.categoryId, dark)
        Slice(s.categoryId ?: "none", s.categoryId, nameOf(s.categoryId), look.icon, look.color, s.minor)
    }
    if (rest.isNotEmpty()) {
        slices.add(Slice("other-slice", null, "Other (${rest.size})", "•••", otherColor(dark), rest.sumOf { it.minor }))
    }
    return slices
}

data class Arc(val slice: Slice, val dash: Double, val offset: Double)

/** Dash length and start offset of each slice along the ring, with a small gap between slices. */
fun layoutArcs(slices: List<Slice>, circumference: Double): List<Arc> {
    val total = slices.sumOf { it.minor }.toDouble()
    var before = 0L
    return slices.map { slice ->
        val length = slice.minor / total * circumference
        val arc = Arc(
            slice = slice,
            dash = maxOf(0.0, length - (if (slices.size > 1) GAP else 0)),
            offset = before / total * circumference
        )
        before += slice.minor
        arc
    }
}

/** "42%" */
fun percentOf(minor: Long, total: Long): String = "${jsRound(minor.toDouble() / total * 100)}%"
