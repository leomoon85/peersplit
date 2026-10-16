package com.peersplit.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Central helpers for sharing files/images via FileProvider and copying sensitive text.
 */
object ShareUtil {

    private const val SHARE_DIR = "shared"
    // Same value as ClipDescription.EXTRA_IS_SENSITIVE (API 33); harmless on older versions.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    private fun shareDir(context: Context): File =
        File(context.cacheDir, SHARE_DIR).apply { mkdirs() }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "peersplit" }

    /** Writes a bitmap to the share cache and returns a content:// Uri. */
    fun bitmapToUri(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val file = File(shareDir(context), safeFileName(fileName) + ".png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, authority(context), file)
        } catch (e: Exception) {
            null
        }
    }

    /** Writes text to a file in the share cache and returns a content:// Uri. */
    fun textToUri(context: Context, content: String, fileName: String): Uri? {
        return try {
            val file = File(shareDir(context), safeFileName(fileName))
            file.writeText(content, Charsets.UTF_8)
            FileProvider.getUriForFile(context, authority(context), file)
        } catch (e: Exception) {
            null
        }
    }

    /** Shares text, optionally with an attached image, through the system share sheet. */
    fun shareTextWithImage(
        context: Context,
        text: String,
        image: Bitmap?,
        imageName: String,
        chooserTitle: String,
        subject: String? = null
    ) {
        val imageUri = image?.let { bitmapToUri(context, it, imageName) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            if (imageUri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = ClipData.newRawUri(imageName, imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        launchChooser(context, intent, chooserTitle)
    }

    /** Shares a real file attachment (e.g. CSV) through the system share sheet. */
    fun shareFile(
        context: Context,
        content: String,
        fileName: String,
        mimeType: String,
        chooserTitle: String,
        subject: String? = null
    ) {
        val uri = textToUri(context, content, fileName)
        if (uri == null) {
            Toast.makeText(context, "Could not create export file", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchChooser(context, intent, chooserTitle)
    }

    /** Copies text to clipboard flagged as sensitive (hidden from clipboard previews on Android 13+). */
    fun copySensitive(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)
    }

    private fun launchChooser(context: Context, intent: Intent, title: String) {
        try {
            val chooser = Intent.createChooser(intent, title)
            if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
        }
    }
}
