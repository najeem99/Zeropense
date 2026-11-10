package com.najeemfasil.expense.core

import kotlin.random.Random

private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

/** Collision-resistant enough for a single-device, local-first database. */
fun newId(): String {
    val sb = StringBuilder(System.currentTimeMillis().toString(36))
    repeat(8) { sb.append(BASE36[Random.nextInt(36)]) }
    return sb.toString()
}

/**
 * Stable hash (32-bit FNV-1a over UTF-16 code units, 8 hex digits) used to recognise an SMS that
 * was already imported. Must stay bit-for-bit identical to the Expo app's `hashString`, or SMS in
 * a carried-over database would be imported again.
 */
fun hashString(input: String): String {
    var h = 0x811c9dc5.toInt()
    for (ch in input) {
        h = h xor ch.code
        h *= 0x01000193
    }
    return (h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}
