package com.example.swipe.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

private fun Context.lifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/**
 * Watches the front camera. Step 1: the person looks straight, and that frame is kept as the selfie.
 * Step 2: they must turn their head to one side, on the same tracked face, within 12 seconds.
 * The head angle comes from ML Kit and runs on the phone.
 */
private class TurnAnalyzer(
    private val onHint: (String) -> Unit,
    private val onDone: (ByteArray) -> Unit
) : ImageAnalysis.Analyzer {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.3f)
            .enableTracking()
            .build()
    )
    @Volatile private var busy = false
    @Volatile private var finished = false
    private var straight: ByteArray? = null
    private var trackId: Int? = null
    private var stable = 0
    private var turned = 0
    private var startedAt = 0L
    private var lastHint = ""

    private fun hint(s: String) {
        if (s != lastHint) { lastHint = s; onHint(s) }
    }

    private fun reset() {
        straight = null; trackId = null; stable = 0; turned = 0
    }

    fun close() {
        finished = true
        detector.close()
    }

    override fun analyze(proxy: ImageProxy) {
        if (finished || busy) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val bmp = runCatching { proxy.toBitmap() }.getOrNull()
        proxy.close()
        if (bmp == null) return
        busy = true
        detector.process(InputImage.fromBitmap(bmp, rotation))
            .addOnSuccessListener { handle(it, bmp, rotation) }
            .addOnCompleteListener { busy = false }
    }

    private fun handle(faces: List<Face>, bmp: Bitmap, rotation: Int) {
        if (finished) return
        if (faces.size > 1) {
            reset(); hint("Only you should be in the frame"); return
        }
        val face = faces.firstOrNull()
        if (face == null) {
            if (straight == null) { stable = 0; hint("Move your face into the frame") }
            return // while turned far to the side the face may briefly disappear, so keep waiting
        }
        val yaw = abs(face.headEulerAngleY)
        val now = SystemClock.elapsedRealtime()
        if (straight == null) {
            if (yaw < 10f && abs(face.headEulerAngleZ) < 15f) stable++ else { stable = 0; hint("Look straight at the camera") }
            if (stable >= 4) {
                straight = toJpeg(bmp, rotation)
                trackId = face.trackingId
                startedAt = now
                turned = 0
                hint("Now slowly turn your head to one side")
            }
        } else {
            if (now - startedAt > 12_000) {
                reset(); hint("Time's up. Look straight at the camera to try again"); return
            }
            if (trackId != null && face.trackingId != null && face.trackingId != trackId) {
                reset(); hint("Look straight at the camera"); return
            }
            if (yaw >= 22f) turned++ else turned = 0
            if (turned >= 2) {
                finished = true
                hint("Got it")
                onDone(straight!!)
            }
        }
    }

    private fun toJpeg(bmp: Bitmap, rotation: Int): ByteArray {
        var out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val s = 800f / maxOf(out.width, out.height)
        if (s < 1f) out = Bitmap.createScaledBitmap(out, (out.width * s).toInt(), (out.height * s).toInt(), true)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}

@Composable
fun LiveSelfie(onDone: (ByteArray) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val owner = remember { context.lifecycleOwner() }
    val doneNow by rememberUpdatedState(onDone)
    var message by remember { mutableStateOf("Look straight at the camera") }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analyzer = remember { TurnAnalyzer(onHint = { message = it }, onDone = { doneNow(it) }) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            p.unbindAll()
            if (owner != null) p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            analyzer.close()
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Text(
            message,
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.9f), contentColor = Color.Black),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)
        ) { Text("Cancel") }
    }
}
