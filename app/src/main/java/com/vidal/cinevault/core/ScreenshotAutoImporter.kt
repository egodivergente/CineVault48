package com.vidal.cinevault.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.vidal.cinevault.model.ImageMetadata
import com.vidal.cinevault.model.ImageType

/** Finds new Samsung screenshots in MediaStore and imports managed copies automatically. */
class ScreenshotAutoImporter(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Returns whether Android granted access to the complete photo library. */
    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Imports screenshots created since the previous scan.
     * The first scan establishes a baseline, so existing screenshots are not imported in bulk.
     */
    fun importNewScreenshots(): Int {
        if (!hasPermission()) return 0
        val screenshots = queryScreenshots()
        if (!preferences.contains(KEY_LAST_ID)) {
            preferences.edit().putLong(KEY_LAST_ID, screenshots.maxOfOrNull { it.id } ?: 0L).apply()
            return 0
        }
        val lastId = preferences.getLong(KEY_LAST_ID, 0L)
        val pending = screenshots.filter { it.id > lastId }.sortedBy { it.id }
        if (pending.isEmpty()) return 0

        val manager = ScreenshotManager(context)
        val importer = UriImporter(context)
        val sourceStore = SourceUriStore(context)
        var imported = 0
        var highestProcessed = lastId
        for (source in pending) {
            var staged: UriImporter.StagedImage? = null
            try {
                staged = importer.stage(source.uri)
                val record = manager.addImage(
                    staged.file.absolutePath,
                    ImageMetadata(
                        project = "Capturas automáticas",
                        originalName = staged.displayName,
                        type = ImageType.FRAME,
                        notes = "Detectada automáticamente en Samsung Screenshots",
                        mimeType = staged.mimeType
                    )
                )
                sourceStore.save(record.id, source.uri)
                imported++
            } finally {
                staged?.file?.delete()
                highestProcessed = maxOf(highestProcessed, source.id)
            }
        }
        preferences.edit().putLong(KEY_LAST_ID, highestProcessed).apply()
        return imported
    }

    /** Queries screenshot albums without depending on a Samsung-specific absolute path. */
    private fun queryScreenshots(): List<MediaImage> {
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        }.toTypedArray()
        val result = mutableListOf<MediaImage>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media._ID} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val bucketIndex = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex).orEmpty()
                val path = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                val bucket = if (bucketIndex >= 0) cursor.getString(bucketIndex).orEmpty() else ""
                val marker = "$path/$bucket/$name".lowercase()
                if (marker.contains("screenshot") || marker.contains("captura")) {
                    result += MediaImage(
                        id,
                        Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    )
                }
            }
        }
        return result
    }

    private data class MediaImage(val id: Long, val uri: Uri)

    private companion object {
        const val PREFERENCES = "automatic_screenshot_import"
        const val KEY_LAST_ID = "last_media_store_id"
    }
}
