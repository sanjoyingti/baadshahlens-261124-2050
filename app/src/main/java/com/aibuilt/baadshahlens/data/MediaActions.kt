package com.aibuilt.baadshahlens.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaActions {

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun share(context: Context, file: File, isVideo: Boolean) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    suspend fun saveToPictures(context: Context, file: File, isVideo: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        if (isVideo) "baadshah_${System.currentTimeMillis()}.mp4"
                        else "baadshah_${System.currentTimeMillis()}.jpg"
                    )
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        if (isVideo) "video/mp4" else "image/jpeg"
                    )
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/BaadshahLens")
                }
                val collection = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val uri = context.contentResolver.insert(collection, values) ?: error("no target")
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("no stream")
                true
            }.getOrDefault(false)
        }
}
