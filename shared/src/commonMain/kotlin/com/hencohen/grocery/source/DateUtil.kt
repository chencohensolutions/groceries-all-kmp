package com.hencohen.grocery.source

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * "YYYY-MM-DD" for today minus [daysBack], in Israel time (UTC+2; close enough for picking which
 * daily listing pages to read — callers always look back a day or two anyway).
 * Kept dependency-free instead of pulling in kotlinx-datetime for one function.
 */
@OptIn(ExperimentalTime::class)
internal fun israelDate(daysBack: Int = 0, nowMillis: Long = Clock.System.now().toEpochMilliseconds()): String {
    val israelMillis = nowMillis + 2 * 3_600_000L
    val days = israelMillis.floorDiv(86_400_000L) - daysBack
    return civilFromDays(days)
}

/** Howard Hinnant's days-to-civil algorithm. */
internal fun civilFromDays(epochDays: Long): String {
    val z = epochDays + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
