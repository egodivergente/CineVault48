package com.vidal.cinevault.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.vidal.cinevault.model.ImageRecord
import java.io.File

/**
 * One-way backup mirror to any Android document provider selected by the user,
 * including local folders and providers exposed by Google Drive or Dropbox.
 */
class SyncFolderManager(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Saves long-lived read/write permission for the chosen document tree. */
    fun setFolder(uri: Uri) {
        val previous = configuredUri()
        if (previous != null && previous != uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    /** Removes the configured mirror without deleting any remote files. */
    fun clearFolder() {
        configuredUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    /** Returns true when a document tree is currently linked. */
    fun isConfigured(): Boolean = configuredUri() != null

    /** Copies or replaces one permanent image inside the selected backup folder. */
    fun mirror(image: ImageRecord): Boolean {
        val treeUri = configuredUri() ?: return false
        val source = File(image.filePath)
        if (!source.isFile) return false
        val resolver = context.contentResolver
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        val existing = findChild(treeUri, rootDocumentId, image.fileName)
        val destination = existing ?: DocumentsContract.createDocument(
            resolver,
            rootDocumentUri,
            image.mimeType.ifBlank { "image/*" },
            image.fileName
        ) ?: return false
        resolver.openOutputStream(destination, "wt").use { output ->
            requireNotNull(output) { "Cannot open linked backup folder" }
            source.inputStream().use { input -> input.copyTo(output) }
        }
        return true
    }

    /** Resolves the persisted document-tree URI, if one exists. */
    private fun configuredUri(): Uri? = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** Finds an existing child by display name so mirrors replace rather than duplicate. */
    private fun findChild(treeUri: Uri, parentDocumentId: String, displayName: String): Uri? {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            }
        }
        return null
    }

    companion object {
        private const val PREFERENCES = "cinevault_settings"
        private const val KEY_TREE_URI = "sync_tree_uri"
    }
}
