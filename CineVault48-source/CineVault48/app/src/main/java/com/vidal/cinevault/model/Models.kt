package com.vidal.cinevault.model

/** Categories used to organize cinematic AI screenshots. */
enum class ImageType(val databaseValue: String, val displayName: String) {
    PROMPT("prompt", "Prompt"),
    FRAME("frame", "Frame"),
    REFERENCE("reference", "Referencia");

    companion object {
        /** Resolves a persisted value without crashing on an unknown future value. */
        fun fromDatabase(value: String): ImageType =
            entries.firstOrNull { it.databaseValue == value } ?: FRAME
    }
}

/** Lifecycle states persisted in SQLite. */
enum class ImageStatus(val databaseValue: String) {
    TEMP("temp"),
    PERMANENT("permanent"),
    TRASH("trash"),
    DELETED("deleted");

    companion object {
        /** Resolves a persisted lifecycle state. */
        fun fromDatabase(value: String): ImageStatus =
            entries.firstOrNull { it.databaseValue == value } ?: TEMP
    }
}

/** User-supplied metadata attached to an imported image. */
data class ImageMetadata(
    val project: String = "Sin proyecto",
    val type: ImageType = ImageType.FRAME,
    val notes: String = "",
    val originalName: String = "image",
    val mimeType: String = "image/*"
)

/** Complete database projection for one managed image. */
data class ImageRecord(
    val id: String,
    val projectId: Long,
    val projectName: String,
    val originalName: String,
    val fileName: String,
    val filePath: String,
    val thumbnailPath: String?,
    val type: ImageType,
    val status: ImageStatus,
    val notes: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val createdAt: Long,
    val expiresAt: Long?,
    val warningAt: Long?,
    val notifiedAt: Long?,
    val trashedAt: Long?,
    val deleteAfter: Long?,
    val deletedAt: Long?
)

/** Optional filters accepted by ScreenshotManager.search(). */
data class SearchFilter(
    val project: String? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val type: ImageType? = null,
    val statuses: Set<ImageStatus> = setOf(ImageStatus.TEMP, ImageStatus.PERMANENT)
)

/** Result of a cleanup pass, including dry-run reporting. */
data class CleanupReport(
    val movedToTrash: List<ImageRecord> = emptyList(),
    val deleted: List<ImageRecord> = emptyList(),
    val simulatedTrashMoves: List<ImageRecord> = emptyList(),
    val simulatedDeletes: List<ImageRecord> = emptyList(),
    val errors: List<String> = emptyList()
)
