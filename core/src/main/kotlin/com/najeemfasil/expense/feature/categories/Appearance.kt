package com.najeemfasil.expense.feature.categories

import kotlin.math.abs

/** An emoji and a colour (0xRRGGBB) for a category. */
data class CategoryLook(val icon: String, val color: Int)

/**
 * Validated 8-hue categorical palette (blue, orange, aqua, yellow, magenta, green, violet, red),
 * with separate steps for each theme. Checked for colour-blind separation on both surfaces.
 */
private val PALETTE_LIGHT = intArrayOf(0x2a78d6, 0xeb6834, 0x1baf7a, 0xeda100, 0xe87ba4, 0x008300, 0x6250d6, 0xe34948)
private val PALETTE_DARK = intArrayOf(0x3987e5, 0xd95926, 0x199e70, 0xc98500, 0xd55181, 0x008300, 0x9085e9, 0xe66767)
private const val NEUTRAL_LIGHT = 0x8a8984
private const val NEUTRAL_DARK = 0x8f8e88

private const val BLUE = 0
private const val ORANGE = 1
private const val AQUA = 2
private const val YELLOW = 3
private const val MAGENTA = 4
private const val GREEN = 5
private const val VIOLET = 6
private const val RED = 7

/** Each category keeps its colour everywhere (icon, pill, chart), whatever its rank. */
private val LOOKS: Map<String, Pair<String, Int?>> = mapOf(
    "groceries" to ("🛒" to GREEN),
    "restaurant" to ("🍽️" to ORANGE),
    "transport" to ("🚕" to BLUE),
    "fuel" to ("⛽" to YELLOW),
    "shopping" to ("🛍️" to MAGENTA),
    "bills" to ("💡" to AQUA),
    "rent" to ("🏠" to VIOLET),
    "subscriptions" to ("🔁" to RED),
    "health" to ("💊" to RED),
    "entertainment" to ("🎬" to MAGENTA),
    "travel" to ("✈️" to BLUE),
    "transfers" to ("↔️" to AQUA),
    "remittance" to ("💸" to ORANGE),
    "other" to ("🏷️" to null),
    "salary" to ("💼" to GREEN),
    "refund" to ("↩️" to AQUA),
    "transfer-in" to ("📥" to BLUE),
    "other-income" to ("💰" to VIOLET)
)

private fun palette(dark: Boolean) = if (dark) PALETTE_DARK else PALETTE_LIGHT

private fun neutral(dark: Boolean) = if (dark) NEUTRAL_DARK else NEUTRAL_LIGHT

fun categoryLook(categoryId: String?, dark: Boolean = false): CategoryLook {
    if (categoryId == null) return CategoryLook("❔", neutral(dark))
    LOOKS[categoryId]?.let { (icon, slot) ->
        return CategoryLook(icon, if (slot == null) neutral(dark) else palette(dark)[slot])
    }
    // Categories the user added: a stable slot from the id (the same hash the Expo app computed).
    val slot = (abs(categoryId.hashCode().toLong()) % 8).toInt()
    return CategoryLook("🏷️", palette(dark)[slot])
}

/** Colour for the chart's "Other" slice. */
fun otherColor(dark: Boolean): Int = neutral(dark)

/** Alpha (out of 255) of the accent colour behind icons and pills. */
const val TINT_ALPHA = 0x26

/** Colour for a single-series chart (the first palette hue). */
fun seriesColor(dark: Boolean): Int = palette(dark)[BLUE]
