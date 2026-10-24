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

private val CJK = Regex("""(?<!\d)(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})""")
private val SPACED = Regex("""(?<!\d)((?:19|20)\d{2})[ ]+(\d{1,2})[ ]+(\d{1,2})(?!\d)""")
private val EIGHT = Regex("""(?<!\d)((?:19|20)\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])(?!\d)""")
private val CHINA_ID = Regex("""(?<!\d)[1-9]\d{5}((?:19|20)\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)""")
private val YYMMDD_IC = Regex("""(?<!\d)(\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])[-\s]?\d{2}[-\s]?\d{4}(?!\d)""") // Malaysia style 900512-14-5678
private val MRZ = Regex("""(?<!\d)(\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d[MFmf<]""")                 // passport and ID card machine-readable lines

/**
 * Finds the birth date in text read from an ID. The birth date is normally the earliest date on the card.
 * If day and month could be swapped (04/03/1990), or the century is unclear (a two-digit year), every reading is
 * returned and the server decides using the age on the profile.
 * Understands: 12/05/1990, 12.05.1990, 1990-05-12, 12 MAY 1990, 1990年5月12日, 19900512, Chinese ID numbers,
 * Malaysian IC numbers and passport machine-readable lines.
 */
fun parseDobCandidates(text: String, nowY: Int = Calendar.getInstance().get(Calendar.YEAR)): List<String> {
    val found = mutableListOf<List<Ymd>>()
    val compact = text.replace(Regex("\\s+"), "") // some cards are read with stray spaces inside long numbers

    fun add(vararg options: Ymd?) {
        val ok = options.filterNotNull().filter { valid(it.y, it.m, it.d, nowY) }
        if (ok.isNotEmpty()) found.add(ok)
    }

    NUM.findAll(text).forEach { m ->
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val y = m.groupValues[3].toInt()
        add(Ymd(y, b, a), if (a != b) Ymd(y, a, b) else null)
    }
    ISO.findAll(text).forEach { m -> add(Ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) }
    CJK.findAll(text).forEach { m -> add(Ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) }
    SPACED.findAll(text).forEach { m -> add(Ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) }
    NAMED.findAll(text).forEach { m ->
        val mo = MONTHS.indexOf(m.groupValues[2].take(3).uppercase()) + 1
        if (mo > 0) add(Ymd(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt()))
    }
    EIGHT.findAll(text).forEach { m -> add(Ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) }
    CHINA_ID.findAll(compact).forEach { m -> add(Ymd(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) }
    for (re in listOf(YYMMDD_IC, MRZ)) {
        re.findAll(compact).forEach { m ->
            val yy = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[3].toInt()
            add(Ymd(1900 + yy, mo, d), Ymd(2000 + yy, mo, d))
        }
    }
    return found.minByOrNull { g -> g.minOf { it.key } }?.map { it.iso() }.orEmpty()
}

/** What the phone read from the ID photo, and the birth dates it could work out. Nothing here leaves the phone. */
class IdScan(val dates: List<String>, val text: String)

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

suspend fun readIdScan(context: Context, uri: Uri): IdScan {
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
    return IdScan(parseDobCandidates(text), text)
}
