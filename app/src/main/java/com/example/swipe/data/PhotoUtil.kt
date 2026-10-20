package com.example.swipe.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads a picked image, shrinks it to at most 800 px, and returns JPEG bytes (about 100 KB). */
suspend fun readJpeg(context: Context, uri: Uri, maxSide: Int = 800): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = maxSide.toFloat() / maxOf(info.size.width, info.size.height)
                if (s < 1f) {
                    dec.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts)!! }
        }
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }.getOrNull()
}
