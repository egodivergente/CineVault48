package com.vidal.cinevault.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Stages Photo Picker URIs as ordinary files before ScreenshotManager imports them. */
class UriImporter(private val context: Context) {
    data class StagedImage(val file: File, val displayName: String, val mimeType: String)

    /** Copies a content URI to private cache and returns its source metadata. */
    fun stage(uri: Uri): StagedImage {
        val resolver = context.contentResolver
        var displayName = "image"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        val mimeType = resolver.getType(uri) ?: "image/*"
        val stagingDirectory = File(context.cacheDir, "import_staging").apply { mkdirs() }
        val extension = displayName.substringAfterLast('.', "").takeIf { it.length in 2..5 }?.let { ".$it" } ?: ".img"
        val target = File(stagingDirectory, "${UUID.randomUUID()}$extension")
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected image cannot be opened" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return StagedImage(target, displayName, mimeType)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
}
