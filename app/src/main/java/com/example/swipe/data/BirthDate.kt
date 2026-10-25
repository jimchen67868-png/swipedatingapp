package com.example.swipe.data

import java.util.Calendar
import java.util.TimeZone

/** Age in whole years today for a birth date written yyyy-mm-dd, or null if it isn't a date. */
fun ageFromBirthDate(iso: String): Int? {
    val p = iso.split("-")
    if (p.size != 3) return null
    val y = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    val d = p[2].toIntOrNull() ?: return null
    val now = Calendar.getInstance()
    val nowM = now.get(Calendar.MONTH) + 1
    val before = nowM < m || (nowM == m && now.get(Calendar.DAY_OF_MONTH) < d)
    return now.get(Calendar.YEAR) - y - if (before) 1 else 0
}

/** Midnight UTC of a calendar day (month is 0 to 11), the form the date picker uses. */
fun utcMillis(year: Int, month: Int, day: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month, day) }.timeInMillis

fun isoToUtcMillis(iso: String): Long? {
    val p = iso.split("-")
    if (p.size != 3) return null
    return utcMillis(p[0].toIntOrNull() ?: return null, (p[1].toIntOrNull() ?: return null) - 1, p[2].toIntOrNull() ?: return null)
}

fun utcMillisToIso(millis: Long): String {
    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}
