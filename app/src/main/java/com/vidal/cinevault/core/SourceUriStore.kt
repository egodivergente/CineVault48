package com.vidal.cinevault.core

import android.content.Context
import android.net.Uri

/** Persists the original MediaStore URI for automatically detected screenshots. */
class SourceUriStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Associates a managed image with its original Samsung Gallery item. */
    fun save(imageId: String, uri: Uri) {
        preferences.edit().putString(KEY_PREFIX + imageId, uri.toString()).apply()
    }

    /** Returns original Gallery URIs for the supplied managed image IDs. */
    fun get(imageIds: Collection<String>): List<Uri> = imageIds.mapNotNull { imageId ->
        preferences.getString(KEY_PREFIX + imageId, null)?.let(Uri::parse)
    }

    /** Removes mappings after Android confirms deletion of the originals. */
    fun remove(imageIds: Collection<String>) {
        preferences.edit().also { editor -> imageIds.forEach { editor.remove(KEY_PREFIX + it) } }.apply()
    }

    private companion object {
        const val PREFERENCES = "source_media_store"
        const val KEY_PREFIX = "source_uri_"
    }
}
