package com.peersplit.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageInput {
    suspend fun decode(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        fun stream() = BoundedInput(context.contentResolver.openInputStream(uri)
            ?: error("Cannot open image"), 16L * 1024 * 1024)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
        require(options.outWidth.toLong() * options.outHeight <= 100_000_000L) { "Image dimensions are too large" }
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        stream().use { BitmapFactory.decodeStream(it, null, options) } ?: error("Cannot decode image")
    }
}
