package com.example.swipe.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import kotlin.coroutines.resume

private data class Ymd(val y: Int, val m: Int, val d: Int) {
    val key get() = y * 10000 + m * 100 + d
    fun iso() = "%04d-%02d-%02d".format(y, m, d)
}

private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private val NUM = Regex("""(?<!\d)(\d{1,2})[./\- ](\d{1,2})[./\- ](\d{4})(?!\d)""")
private val ISO = Regex("""(?<!\d)(\d{4})[./\-](\d{1,2})[./\-](\d{1,2})(?!\d)""")
private val NAMED = Regex("""(?<!\d)(\d{1,2})\s*([A-Za-z]{3,9})\.?,?\s*(\d{4})(?!\d)""")

private fun valid(y: Int, m: Int, d: Int, nowY: Int): Boolean {
    if (m !in 1..12 || d < 1 || y !in (nowY - 110)..(nowY - 10)) return false // birth date: 10 to 110 years ago
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    return d <= days[m - 1]
}

/**
 * Finds the birth date in text read from an ID. The birth date is normally the earliest date on the card.
 * If day and month could be swapped (04/03/1990), both readings are returned and the server decides.
 */
fun parseDobCandidates(text: String, nowY: Int = Calendar.getInstance().get(Calendar.YEAR)): List<String> {
    val found = mutableListOf<List<Ymd>>()
    NUM.findAll(text).forEach { m ->
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val y = m.groupValues[3].toInt()
        val opts = listOfNotNull(
            if (valid(y, b, a, nowY)) Ymd(y, b, a) else null,
            if (a != b && valid(y, a, b, nowY)) Ymd(y, a, b) else null
        )
        if (opts.isNotEmpty()) found.add(opts)
    }
    ISO.findAll(text).forEach { m ->
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        if (valid(y, mo, d, nowY)) found.add(listOf(Ymd(y, mo, d)))
    }
    NAMED.findAll(text).forEach { m ->
        val d = m.groupValues[1].toInt()
        val mo = MONTHS.indexOf(m.groupValues[2].take(3).uppercase()) + 1
        val y = m.groupValues[3].toInt()
        if (mo > 0 && valid(y, mo, d, nowY)) found.add(listOf(Ymd(y, mo, d)))
    }
    return found.minByOrNull { g -> g.minOf { it.key } }?.map { it.iso() }.orEmpty()
}

/** Reads the text on the ID photo on the phone. The photo is not sent anywhere by this function. */
suspend fun readDobCandidates(context: Context, uri: Uri): List<String> {
    val text = suspendCancellableCoroutine<String> { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        runCatching { InputImage.fromFilePath(context, uri) }
            .onSuccess { image ->
                recognizer.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                    .addOnFailureListener { if (cont.isActive) cont.resume("") }
            }
            .onFailure { if (cont.isActive) cont.resume("") }
    }
    return parseDobCandidates(text)
}
