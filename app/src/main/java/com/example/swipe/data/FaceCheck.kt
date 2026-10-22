package com.example.swipe.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.sqrt

/**
 * First of two photo checks: runs on the phone. It never blocks the upload. The phone's verdict travels with the
 * photo, and the server always runs its own checks too. The photo is accepted only if both pass.
 * If the phone can't run a check (no model file, no network), it reports "ok" and the server decides alone.
 */

private const val MODEL_FILE = "mobilefacenet.tflite" // optional, see app/src/main/assets/README.txt
private const val MIN_SIMILARITY = 0.6f               // cosine similarity needed to count as the same person
private const val SHOW_SCORE = true                   // shows the match score after adding a photo, to help tune MIN_SIMILARITY

private suspend fun detectFaces(bmp: Bitmap): List<Face>? {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build()
    )
    return try {
        suspendCancellableCoroutine { cont ->
            detector.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    } finally {
        detector.close()
    }
}

/** Counts faces and checks face size. Returns a code ("no_face", "many_faces", "too_small"), or null if the photo looks fine. */
suspend fun photoProblem(jpeg: ByteArray, main: Boolean): String? {
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    val found = detectFaces(bmp) ?: return null
    val people = found.filter { it.boundingBox.width().toFloat() / bmp.width >= 0.06f }
    if (people.isEmpty()) return "no_face"
    if (people.size > 1) return "many_faces"
    val ratio = people[0].boundingBox.width().toFloat() / bmp.width
    val needed = if (main) 0.17f else 0.08f
    if (ratio < needed) return "too_small"
    return null
}

// ---------- same-person check with MobileFaceNet (needs the model file) ----------

private var interpreter: Interpreter? = null
private var loadTried = false

private fun interpreter(context: Context): Interpreter? {
    if (!loadTried) {
        loadTried = true
        interpreter = runCatching {
            val bytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            Interpreter(buf)
        }.getOrNull()
    }
    return interpreter
}

/** A length-1 list of numbers that describes a face, or null if there isn't exactly one clear face. */
private suspend fun embedding(context: Context, jpeg: ByteArray): FloatArray? {
    val interp = interpreter(context) ?: return null
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    val people = (detectFaces(bmp) ?: return null).filter { it.boundingBox.width().toFloat() / bmp.width >= 0.06f }
    val face = people.singleOrNull() ?: return null
    val size = interp.getInputTensor(0).shape().getOrNull(1) ?: 112

    // Cut out the face, straightened, as a square of size x size.
    val box = face.boundingBox
    val side = max(box.width(), box.height()) * 1.15f
    val crop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val m = Matrix().apply {
        postTranslate(-box.exactCenterX(), -box.exactCenterY())
        postRotate(face.headEulerAngleZ)
        postScale(size / side, size / side)
        postTranslate(size / 2f, size / 2f)
    }
    Canvas(crop).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))

    val px = IntArray(size * size)
    crop.getPixels(px, 0, size, 0, 0, size, size)
    val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
    for (p in px) {
        input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
        input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
        input.putFloat(((p and 0xFF) - 127.5f) / 128f)
    }
    input.rewind()

    val n = interp.getOutputTensor(0).shape().last()
    val out = Array(1) { FloatArray(n) }
    interp.run(input, out)
    val v = out[0]
    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return if (norm > 0f) FloatArray(n) { v[it] / norm } else null
}

private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { URL(url).openStream().use { it.readBytes() } }.getOrNull()
}

/** Compares the new photo with the main photo on the phone. Returns "different_person:<score>" if they look like different people. */
private suspend fun samePersonProblem(context: Context, jpeg: ByteArray, mainPhotoUrl: String): String? {
    if (interpreter(context) == null) return null
    val reference = download(mainPhotoUrl) ?: return null
    val a = embedding(context, jpeg) ?: return null
    val b = embedding(context, reference) ?: return null
    if (a.size != b.size) return null
    var sim = 0f
    for (i in a.indices) sim += a[i] * b[i]
    val score = "%.2f".format(sim)
    if (SHOW_SCORE) Toast.makeText(context, "Phone face match: $score", Toast.LENGTH_SHORT).show()
    return if (sim < MIN_SIMILARITY) "different_person:$score" else null
}

/**
 * The phone's verdict on the photo that is about to be uploaded, sent to the server in the X-Phone-Check header.
 * The photo is ALWAYS uploaded: the server runs its own checks every time, and refuses the photo if either check fails.
 */
object PhoneCheck {
    private var hash = 0
    private var verdict: String? = null

    @Synchronized fun set(bytes: ByteArray, v: String) { hash = bytes.contentHashCode(); verdict = v }

    @Synchronized fun forBody(bytes: ByteArray): String? = if (verdict != null && bytes.contentHashCode() == hash) verdict else null
}

/**
 * Reads the picked image, shrinks it, and runs the phone checks. It never blocks the photo: the verdict is remembered
 * and travels with the upload, so the server's check always runs too. For an extra photo, pass the main photo's address.
 */
suspend fun readCheckedPhoto(context: Context, uri: Uri, main: Boolean, mainPhotoUrl: String? = null): ByteArray? {
    val jpeg = readJpeg(context, uri) ?: return null
    val verdict = photoProblem(jpeg, main)
        ?: if (!main && mainPhotoUrl != null) samePersonProblem(context, jpeg, mainPhotoUrl) else null
    PhoneCheck.set(jpeg, verdict ?: "ok")
    return jpeg
}
