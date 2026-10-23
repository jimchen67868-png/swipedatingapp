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

/** The straightened face as size x size x 3 numbers (what the model expects), or null if there isn't exactly one clear face. */
private suspend fun faceCrop(bmp: Bitmap, size: Int): FloatArray? {
    val people = (detectFaces(bmp) ?: return null).filter { it.boundingBox.width().toFloat() / bmp.width >= 0.06f }
    val face = people.singleOrNull() ?: return null
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
    val out = FloatArray(size * size * 3)
    var i = 0
    for (p in px) {
        out[i++] = (((p shr 16) and 0xFF) - 127.5f) / 128f
        out[i++] = (((p shr 8) and 0xFF) - 127.5f) / 128f
        out[i++] = ((p and 0xFF) - 127.5f) / 128f
    }
    return out
}

private fun unit(v: FloatArray): FloatArray? {
    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return if (norm > 0f) FloatArray(v.size) { v[it] / norm } else null
}

/**
 * Similarity of two faces from -1 to 1 (higher = more alike), or null if it can't be worked out.
 * Some MobileFaceNet files take two faces at once (input 2 x 112 x 112 x 3), others one face at a time. Both work.
 */
private suspend fun samePersonScore(context: Context, jpegA: ByteArray, jpegB: ByteArray): Float? {
    val interp = interpreter(context) ?: return null
    val shape = interp.getInputTensor(0).shape() // [batch, size, size, 3]
    val batch = shape[0]
    val size = shape[1]
    if (batch != 1 && batch != 2) return null
    val a = faceCrop(BitmapFactory.decodeByteArray(jpegA, 0, jpegA.size) ?: return null, size) ?: return null
    val b = faceCrop(BitmapFactory.decodeByteArray(jpegB, 0, jpegB.size) ?: return null, size) ?: return null
    val n = interp.getOutputTensor(0).shape().last()

    fun input(vararg faces: FloatArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * faces.sumOf { it.size }).order(ByteOrder.nativeOrder())
        for (f in faces) for (x in f) buf.putFloat(x)
        buf.rewind()
        return buf
    }

    val ea: FloatArray
    val eb: FloatArray
    if (batch == 2) {
        val out = Array(2) { FloatArray(n) }
        interp.run(input(a, b), out)
        ea = out[0]
        eb = out[1]
    } else {
        val outA = Array(1) { FloatArray(n) }
        interp.run(input(a), outA)
        val outB = Array(1) { FloatArray(n) }
        interp.run(input(b), outB)
        ea = outA[0]
        eb = outB[0]
    }
    val ua = unit(ea) ?: return null
    val ub = unit(eb) ?: return null
    var sim = 0f
    for (i in ua.indices) sim += ua[i] * ub[i]
    return sim
}

private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { URL(url).openStream().use { it.readBytes() } }.getOrNull()
}

/** Compares the new photo with the main photo on the phone. Returns a message if they look like different people. */
private suspend fun samePersonProblem(context: Context, jpeg: ByteArray, mainPhotoUrl: String): String? {
    if (interpreter(context) == null) return null
    val reference = download(mainPhotoUrl) ?: return null
    val sim = try {
        samePersonScore(context, jpeg, reference)
    } catch (e: Exception) {
        null // any problem with the model: skip this check, the server still decides
    } ?: return null
    val score = "%.2f".format(sim)
    if (sim < MIN_SIMILARITY) return "This photo doesn't look like the person in your main photo. (match $score, needs $MIN_SIMILARITY)"
    if (SHOW_SCORE) Toast.makeText(context, "Phone face match: $score", Toast.LENGTH_SHORT).show()
    return null
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
