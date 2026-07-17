package com.vidal.cinevault.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.vidal.cinevault.data.CineVaultDatabase
import com.vidal.cinevault.model.CleanupReport
import com.vidal.cinevault.model.ImageMetadata
import com.vidal.cinevault.model.ImageRecord
import com.vidal.cinevault.model.ImageStatus
import com.vidal.cinevault.model.SearchFilter
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns the complete lifecycle of imported screenshots.
 *
 * All managed paths are generated internally. Source names never become directory names,
 * which avoids path traversal and makes deletion safe under Android scoped storage.
 */
class ScreenshotManager(
    private val context: Context,
    private val config: AppConfig = AppConfig.load(context),
    private val database: CineVaultDatabase = CineVaultDatabase(context),
    private val clock: Clock = SystemClock,
    rootOverride: File? = null,
    private val dryRunProvider: () -> Boolean = { AppConfig.load(context).dryRun }
) {
    private val lifecyclePolicy = LifecyclePolicy(config)
    private val rootDirectory: File = rootOverride ?: defaultRoot(context, config.rootFolder)
    private val temporaryDirectory = File(rootDirectory, "temp")
    private val permanentDirectory = File(rootDirectory, "permanent")
    private val trashDirectory = File(rootDirectory, "trash")
    private val thumbnailDirectory = File(rootDirectory, "thumbnails")

    init {
        listOf(rootDirectory, temporaryDirectory, permanentDirectory, trashDirectory, thumbnailDirectory)
            .forEach { directory -> check(directory.exists() || directory.mkdirs()) { "Cannot create ${directory.path}" } }
    }

    /**
     * Copies an image into /temp, writes a thumbnail and starts the configured 48-hour timer.
     * The caller keeps ownership of the source file.
     */
    fun addImage(path: String, metadata: ImageMetadata): ImageRecord {
        val source = File(path)
        require(source.isFile && source.canRead()) { "Source image is not readable: $path" }
        val now = clock.nowMillis()
        val id = UUID.randomUUID().toString()
        val extension = safeExtension(metadata.originalName.ifBlank { source.name })
        val fileName = "$id$extension"
        val destination = File(temporaryDirectory, fileName)
        val thumbnail = File(thumbnailDirectory, "$id.jpg")
        val projectId = database.getOrCreateProject(metadata.project, now)

        try {
            source.copyTo(destination, overwrite = false)
            val dimensions = readDimensions(destination)
            val thumbnailPath = createThumbnail(destination, thumbnail)?.absolutePath
            val record = ImageRecord(
                id = id,
                projectId = projectId,
                projectName = metadata.project.trim().ifBlank { "Sin proyecto" }.take(80),
                originalName = metadata.originalName.take(255),
                fileName = fileName,
                filePath = destination.absolutePath,
                thumbnailPath = thumbnailPath,
                type = metadata.type,
                status = ImageStatus.TEMP,
                notes = metadata.notes.trim().take(500),
                mimeType = metadata.mimeType.take(100),
                width = dimensions.first,
                height = dimensions.second,
                sizeBytes = destination.length(),
                createdAt = now,
                expiresAt = lifecyclePolicy.expiresAt(now),
                warningAt = lifecyclePolicy.warningAt(now),
                notifiedAt = null,
                trashedAt = null,
                deleteAfter = null,
                deletedAt = null
            )
            database.insertImage(record)
            database.log(id, "IMAGE_ADDED", "Imported to temporary storage", now)
            return record
        } catch (error: Exception) {
            destination.delete()
            thumbnail.delete()
            throw error
        }
    }

    /** Snake-case compatibility entrypoint matching the original cross-platform specification. */
    @Suppress("FunctionName")
    fun add_image(path: String, metadata: ImageMetadata): ImageRecord = addImage(path, metadata)

    /** Moves a temporary or trash image to /permanent and cancels all deletion deadlines. */
    fun markPermanent(imageId: String): ImageRecord {
        val record = requireManagedImage(imageId)
        if (record.status == ImageStatus.PERMANENT) return record
        require(record.status != ImageStatus.DELETED) { "A deleted image cannot be made permanent" }
        val source = File(record.filePath)
        val destination = File(permanentDirectory, record.fileName)
        moveWithDatabaseRollback(source, destination) {
            database.markPermanent(imageId, destination.absolutePath)
        }
        val now = clock.nowMillis()
        database.log(imageId, "MARKED_PERMANENT", "Moved to permanent storage", now)
        return requireManagedImage(imageId)
    }

    /** Snake-case compatibility entrypoint for marking an image permanent. */
    @Suppress("FunctionName")
    fun mark_permanent(imageId: String): ImageRecord = markPermanent(imageId)

    /** Lists temporary images whose 48-hour deadline has passed without mutating files. */
    fun checkExpired(): List<ImageRecord> = database.expiredTemporary(clock.nowMillis())

    /** Snake-case compatibility entrypoint for listing temporary expiry candidates. */
    @Suppress("FunctionName")
    fun check_expired(): List<ImageRecord> = checkExpired()

    /** Lists trash images whose 24-hour grace period has passed. */
    fun checkTrashExpired(): List<ImageRecord> = database.expiredTrash(clock.nowMillis())

    /** Moves all expired temporary images to /trash, or reports them when dry-run is enabled. */
    fun moveExpiredToTrash(): CleanupReport {
        val now = clock.nowMillis()
        val candidates = database.expiredTemporary(now)
        if (dryRunProvider()) {
            candidates.forEach {
                database.log(it.id, "DRY_RUN_TRASH", "Would move expired image to trash", now)
            }
            return CleanupReport(simulatedTrashMoves = candidates)
        }

        val moved = mutableListOf<ImageRecord>()
        val errors = mutableListOf<String>()
        for (record in candidates) {
            try {
                val source = File(record.filePath)
                val destination = File(trashDirectory, record.fileName)
                val deleteAfter = lifecyclePolicy.deleteAfter(now)
                moveWithDatabaseRollback(source, destination) {
                    database.markTrashed(record.id, destination.absolutePath, now, deleteAfter)
                }
                database.log(record.id, "MOVED_TO_TRASH", "Grace period started", now)
                moved += requireManagedImage(record.id)
            } catch (error: Exception) {
                val message = "${record.id}: ${error.message ?: error.javaClass.simpleName}"
                errors += message
                database.log(record.id, "TRASH_ERROR", message, now)
            }
        }
        return CleanupReport(movedToTrash = moved, errors = errors)
    }

    /** Physically deletes grace-expired trash files and thumbnails, retaining a DB tombstone and log. */
    fun deleteExpired(): CleanupReport {
        val now = clock.nowMillis()
        val candidates = database.expiredTrash(now)
        if (dryRunProvider()) {
            candidates.forEach {
                database.log(it.id, "DRY_RUN_DELETE", "Would permanently delete trash item", now)
            }
            return CleanupReport(simulatedDeletes = candidates)
        }

        val deleted = mutableListOf<ImageRecord>()
        val errors = mutableListOf<String>()
        for (record in candidates) {
            try {
                deleteManagedFiles(record)
                database.markDeleted(record.id, now)
                database.log(record.id, "DELETED", "Physical file and thumbnail removed", now)
                deleted += record
            } catch (error: Exception) {
                val message = "${record.id}: ${error.message ?: error.javaClass.simpleName}"
                errors += message
                database.log(record.id, "DELETE_ERROR", message, now)
            }
        }
        return CleanupReport(deleted = deleted, errors = errors)
    }

    /** Snake-case compatibility entrypoint for final deletion after trash grace. */
    @Suppress("FunctionName")
    fun delete_expired(): CleanupReport = deleteExpired()

    /** Executes warning-independent lifecycle maintenance in the correct order. */
    fun runMaintenance(): CleanupReport {
        val trashReport = moveExpiredToTrash()
        val deleteReport = deleteExpired()
        return CleanupReport(
            movedToTrash = trashReport.movedToTrash,
            deleted = deleteReport.deleted,
            simulatedTrashMoves = trashReport.simulatedTrashMoves,
            simulatedDeletes = deleteReport.simulatedDeletes,
            errors = trashReport.errors + deleteReport.errors
        )
    }

    /** Restores a trash item to /temp with a new full 48-hour window. */
    fun restore(imageId: String): ImageRecord {
        val record = requireManagedImage(imageId)
        require(record.status == ImageStatus.TRASH) { "Only trash items can be restored" }
        val now = clock.nowMillis()
        val source = File(record.filePath)
        val destination = File(temporaryDirectory, record.fileName)
        moveWithDatabaseRollback(source, destination) {
            database.markRestored(
                imageId,
                destination.absolutePath,
                lifecyclePolicy.expiresAt(now),
                lifecyclePolicy.warningAt(now)
            )
        }
        database.log(imageId, "RESTORED", "Returned to temp with a fresh retention window", now)
        return requireManagedImage(imageId)
    }

    /** Immediately deletes a selected trash item, respecting dry-run mode. */
    fun deleteNow(imageId: String): Boolean {
        val record = requireManagedImage(imageId)
        require(record.status == ImageStatus.TRASH) { "Only trash items can be deleted manually" }
        val now = clock.nowMillis()
        if (dryRunProvider()) {
            database.log(imageId, "DRY_RUN_DELETE_NOW", "Would delete selected trash item", now)
            return false
        }
        deleteManagedFiles(record)
        database.markDeleted(imageId, now)
        database.log(imageId, "DELETED_MANUALLY", "User confirmed immediate deletion", now)
        return true
    }

    /** Queries the SQLite index by project, date range, type and lifecycle state. */
    fun search(filter: SearchFilter = SearchFilter()): List<ImageRecord> = database.search(filter)

    /** Queries the three requested search dimensions using an inclusive epoch-millisecond range. */
    fun search(project: String?, dateRange: LongRange?, type: com.vidal.cinevault.model.ImageType?): List<ImageRecord> =
        database.search(
            SearchFilter(
                project = project,
                dateFrom = dateRange?.first,
                dateTo = dateRange?.last,
                type = type
            )
        )

    /** Finds one managed image by ID. */
    fun getImage(imageId: String): ImageRecord? = database.getImage(imageId)

    /** Returns images that should appear in the grouped 24-hour warning notification. */
    fun notificationCandidates(): List<ImageRecord> = database.warningCandidates(clock.nowMillis())

    /** Records successful warning delivery for the provided items. */
    fun markWarningsNotified(imageIds: Collection<String>) {
        val now = clock.nowMillis()
        database.markNotified(imageIds, now)
        imageIds.forEach { database.log(it, "WARNING_NOTIFIED", "24-hour warning posted", now) }
    }

    /** Returns project labels currently present in the database. */
    fun listProjects(): List<String> = database.listProjects()

    /** Returns the app-owned root directory for diagnostics and backup guidance. */
    fun managedRoot(): File = rootDirectory

    /** Resolves an ID or throws before a file operation can run. */
    private fun requireManagedImage(imageId: String): ImageRecord =
        requireNotNull(database.getImage(imageId)) { "Unknown image: $imageId" }

    /** Moves a file and rolls it back if the corresponding SQLite update fails. */
    private fun moveWithDatabaseRollback(source: File, destination: File, databaseUpdate: () -> Unit) {
        require(source.isFile) { "Managed file is missing: ${source.path}" }
        ensureInsideRoot(source)
        ensureInsideRoot(destination)
        check(!destination.exists()) { "Destination already exists: ${destination.path}" }
        destination.parentFile?.mkdirs()
        moveFile(source, destination)
        try {
            databaseUpdate()
        } catch (error: Exception) {
            runCatching { moveFile(destination, source) }
            throw error
        }
    }

    /** Moves on the same volume or falls back to copy-and-delete across volumes. */
    private fun moveFile(source: File, destination: File) {
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = false)
            check(source.delete()) { "Copied but could not remove original ${source.path}" }
        }
    }

    /** Removes the original and thumbnail after validating both managed paths. */
    private fun deleteManagedFiles(record: ImageRecord) {
        val image = File(record.filePath)
        ensureInsideRoot(image)
        if (image.exists()) check(image.delete()) { "Could not delete ${image.path}" }
        record.thumbnailPath?.let { path ->
            val thumbnail = File(path)
            ensureInsideRoot(thumbnail)
            if (thumbnail.exists()) check(thumbnail.delete()) { "Could not delete ${thumbnail.path}" }
        }
    }

    /** Rejects any path that escapes the app-owned root after canonicalization. */
    private fun ensureInsideRoot(file: File) {
        val rootPath = rootDirectory.canonicalFile.path + File.separator
        val candidate = file.canonicalFile.path
        require(candidate.startsWith(rootPath)) { "Refusing file operation outside managed root" }
    }

    /** Reads image bounds without allocating the full bitmap. */
    private fun readDimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return max(0, options.outWidth) to max(0, options.outHeight)
    }

    /** Creates a bounded JPEG preview and returns null for unsupported formats. */
    private fun createThumbnail(source: File, target: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > config.thumbnailSizePx * 2 ||
            bounds.outHeight / sample > config.thumbnailSizePx * 2
        ) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val scale = minOf(
            1f,
            config.thumbnailSizePx.toFloat() / max(decoded.width, decoded.height).toFloat()
        )
        val width = max(1, (decoded.width * scale).roundToInt())
        val height = max(1, (decoded.height * scale).roundToInt())
        val scaled = if (width == decoded.width && height == decoded.height) decoded
        else Bitmap.createScaledBitmap(decoded, width, height, true)
        target.outputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)) { "Thumbnail compression failed" }
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return target
    }

    /** Whitelists extensions so generated destinations cannot contain unsafe suffixes. */
    private fun safeExtension(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "heic", "heif" -> ".$extension"
            else -> ".img"
        }
    }

    companion object {
        /** Selects app-specific external pictures storage, falling back to internal storage. */
        private fun defaultRoot(context: Context, rootFolder: String): File {
            val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            return if (pictures != null) File(pictures, rootFolder) else File(context.filesDir, rootFolder)
        }
    }
}
