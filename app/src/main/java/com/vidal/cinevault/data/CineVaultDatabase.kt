package com.vidal.cinevault.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vidal.cinevault.model.ImageRecord
import com.vidal.cinevault.model.ImageStatus
import com.vidal.cinevault.model.ImageType
import com.vidal.cinevault.model.SearchFilter

/** SQLite persistence for images, projects and append-only audit logs. */
class CineVaultDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    /** Enables foreign-key enforcement for every readable and writable connection. */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /** Creates the v1 schema, indexes and default project. */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE proyectos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE imagenes (
                id TEXT PRIMARY KEY,
                project_id INTEGER NOT NULL,
                original_name TEXT NOT NULL,
                file_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                thumbnail_path TEXT,
                type TEXT NOT NULL CHECK(type IN ('prompt','frame','reference')),
                status TEXT NOT NULL CHECK(status IN ('temp','permanent','trash','deleted')),
                notes TEXT NOT NULL DEFAULT '',
                mime_type TEXT NOT NULL,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                warning_at INTEGER,
                notified_at INTEGER,
                trashed_at INTEGER,
                delete_after INTEGER,
                deleted_at INTEGER,
                FOREIGN KEY(project_id) REFERENCES proyectos(id) ON DELETE RESTRICT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                image_id TEXT,
                action TEXT NOT NULL,
                details TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(image_id) REFERENCES imagenes(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_images_status_expiry ON imagenes(status, expires_at)")
        db.execSQL("CREATE INDEX idx_images_trash_delete ON imagenes(status, delete_after)")
        db.execSQL("CREATE INDEX idx_images_project_date ON imagenes(project_id, created_at)")
        db.execSQL("CREATE INDEX idx_images_type ON imagenes(type)")
        db.execSQL("CREATE INDEX idx_logs_image_date ON logs(image_id, created_at)")
        val values = ContentValues().apply {
            put("name", "Sin proyecto")
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("proyectos", null, values)
    }

    /** Reserved migration entrypoint; version 1 has no predecessor to migrate. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 has no migrations yet. Future versions must migrate without data loss.
    }

    /** Returns an existing case-insensitive project or creates it atomically. */
    @Synchronized
    fun getOrCreateProject(name: String, now: Long): Long {
        val normalized = name.trim().ifBlank { "Sin proyecto" }.take(80)
        readableDatabase.query(
            "proyectos",
            arrayOf("id"),
            "name = ? COLLATE NOCASE",
            arrayOf(normalized),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        val values = ContentValues().apply {
            put("name", normalized)
            put("created_at", now)
        }
        return try {
            writableDatabase.insertOrThrow("proyectos", null, values)
        } catch (_: Exception) {
            readableDatabase.query(
                "proyectos", arrayOf("id"), "name = ? COLLATE NOCASE",
                arrayOf(normalized), null, null, null, "1"
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Could not create project" }
                cursor.getLong(0)
            }
        }
    }

    /** Inserts a fully materialized image row. */
    @Synchronized
    fun insertImage(image: ImageRecord) {
        writableDatabase.insertOrThrow("imagenes", null, image.toValues())
    }

    /** Finds one image by stable UUID. */
    @Synchronized
    fun getImage(imageId: String): ImageRecord? {
        val sql = BASE_SELECT + " WHERE i.id = ? LIMIT 1"
        readableDatabase.rawQuery(sql, arrayOf(imageId)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toImageRecord() else null
        }
    }

    /** Queries active or trash records using project, range and type filters. */
    @Synchronized
    fun search(filter: SearchFilter): List<ImageRecord> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (filter.statuses.isEmpty()) return emptyList()
        where += "i.status IN (${filter.statuses.joinToString(",") { "?" }})"
        args += filter.statuses.map { it.databaseValue }
        filter.project?.trim()?.takeIf { it.isNotBlank() }?.let {
            where += "p.name LIKE ? COLLATE NOCASE"
            args += "%$it%"
        }
        filter.dateFrom?.let {
            where += "i.created_at >= ?"
            args += it.toString()
        }
        filter.dateTo?.let {
            where += "i.created_at <= ?"
            args += it.toString()
        }
        filter.type?.let {
            where += "i.type = ?"
            args += it.databaseValue
        }
        val sql = BASE_SELECT + " WHERE " + where.joinToString(" AND ") + " ORDER BY i.created_at DESC"
        return queryRecords(sql, args.toTypedArray())
    }

    /** Returns temporary images whose 48-hour deadline has passed. */
    @Synchronized
    fun expiredTemporary(now: Long): List<ImageRecord> = queryRecords(
        BASE_SELECT + " WHERE i.status = 'temp' AND i.expires_at <= ? ORDER BY i.expires_at",
        arrayOf(now.toString())
    )

    /** Returns trash items whose grace period has ended. */
    @Synchronized
    fun expiredTrash(now: Long): List<ImageRecord> = queryRecords(
        BASE_SELECT + " WHERE i.status = 'trash' AND i.delete_after <= ? ORDER BY i.delete_after",
        arrayOf(now.toString())
    )

    /** Returns unnotified temporary items inside the warning window. */
    @Synchronized
    fun warningCandidates(now: Long): List<ImageRecord> = queryRecords(
        BASE_SELECT + " WHERE i.status = 'temp' AND i.notified_at IS NULL " +
            "AND i.warning_at <= ? AND i.expires_at > ? ORDER BY i.expires_at",
        arrayOf(now.toString(), now.toString())
    )

    /** Updates a record after moving it into the permanent directory. */
    @Synchronized
    fun markPermanent(imageId: String, newPath: String) {
        val values = ContentValues().apply {
            put("status", ImageStatus.PERMANENT.databaseValue)
            put("file_path", newPath)
            putNull("expires_at")
            putNull("warning_at")
            putNull("notified_at")
            putNull("trashed_at")
            putNull("delete_after")
        }
        require(writableDatabase.update("imagenes", values, "id = ?", arrayOf(imageId)) == 1) {
            "Image not found: $imageId"
        }
    }

    /** Updates a record after moving an expired file into the trash directory. */
    @Synchronized
    fun markTrashed(imageId: String, newPath: String, trashedAt: Long, deleteAfter: Long) {
        val values = ContentValues().apply {
            put("status", ImageStatus.TRASH.databaseValue)
            put("file_path", newPath)
            put("trashed_at", trashedAt)
            put("delete_after", deleteAfter)
        }
        require(writableDatabase.update("imagenes", values, "id = ?", arrayOf(imageId)) == 1) {
            "Image not found: $imageId"
        }
    }

    /** Restores a trash item to temporary status with a fresh retention window. */
    @Synchronized
    fun markRestored(imageId: String, newPath: String, expiresAt: Long, warningAt: Long) {
        val values = ContentValues().apply {
            put("status", ImageStatus.TEMP.databaseValue)
            put("file_path", newPath)
            put("expires_at", expiresAt)
            put("warning_at", warningAt)
            putNull("notified_at")
            putNull("trashed_at")
            putNull("delete_after")
        }
        require(writableDatabase.update("imagenes", values, "id = ?", arrayOf(imageId)) == 1) {
            "Image not found: $imageId"
        }
    }

    /** Retains a tombstone row while removing paths after physical deletion. */
    @Synchronized
    fun markDeleted(imageId: String, deletedAt: Long) {
        val values = ContentValues().apply {
            put("status", ImageStatus.DELETED.databaseValue)
            put("file_path", "")
            putNull("thumbnail_path")
            put("deleted_at", deletedAt)
        }
        require(writableDatabase.update("imagenes", values, "id = ?", arrayOf(imageId)) == 1) {
            "Image not found: $imageId"
        }
    }

    /** Marks warning delivery so the same image is not announced twice. */
    @Synchronized
    fun markNotified(imageIds: Collection<String>, notifiedAt: Long) {
        if (imageIds.isEmpty()) return
        val placeholders = imageIds.joinToString(",") { "?" }
        val values = ContentValues().apply { put("notified_at", notifiedAt) }
        writableDatabase.update("imagenes", values, "id IN ($placeholders)", imageIds.toTypedArray())
    }

    /** Appends an auditable lifecycle event. */
    @Synchronized
    fun log(imageId: String?, action: String, details: String, now: Long) {
        val values = ContentValues().apply {
            if (imageId == null) putNull("image_id") else put("image_id", imageId)
            put("action", action.take(80))
            put("details", details.take(2000))
            put("created_at", now)
        }
        writableDatabase.insertOrThrow("logs", null, values)
    }

    /** Lists project names for filters and import dialogs. */
    @Synchronized
    fun listProjects(): List<String> {
        val names = mutableListOf<String>()
        readableDatabase.query("proyectos", arrayOf("name"), null, null, null, null, "name COLLATE NOCASE").use {
            while (it.moveToNext()) names += it.getString(0)
        }
        return names
    }

    /** Counts log entries with the supplied action; useful for diagnostics and tests. */
    @Synchronized
    fun countLogs(action: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM logs WHERE action = ?", arrayOf(action)).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** Maps an arbitrary image select into domain records. */
    private fun queryRecords(sql: String, args: Array<String>): List<ImageRecord> {
        val records = mutableListOf<ImageRecord>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) records += cursor.toImageRecord()
        }
        return records
    }

    /** Converts a domain record into SQLite values including nullable deadlines. */
    private fun ImageRecord.toValues() = ContentValues().apply {
        put("id", id)
        put("project_id", projectId)
        put("original_name", originalName)
        put("file_name", fileName)
        put("file_path", filePath)
        if (thumbnailPath == null) putNull("thumbnail_path") else put("thumbnail_path", thumbnailPath)
        put("type", type.databaseValue)
        put("status", status.databaseValue)
        put("notes", notes)
        put("mime_type", mimeType)
        put("width", width)
        put("height", height)
        put("size_bytes", sizeBytes)
        put("created_at", createdAt)
        expiresAt?.let { put("expires_at", it) } ?: putNull("expires_at")
        warningAt?.let { put("warning_at", it) } ?: putNull("warning_at")
        notifiedAt?.let { put("notified_at", it) } ?: putNull("notified_at")
        trashedAt?.let { put("trashed_at", it) } ?: putNull("trashed_at")
        deleteAfter?.let { put("delete_after", it) } ?: putNull("delete_after")
        deletedAt?.let { put("deleted_at", it) } ?: putNull("deleted_at")
    }

    /** Maps the joined image/project cursor projection into one immutable record. */
    private fun Cursor.toImageRecord(): ImageRecord = ImageRecord(
        id = getString(getColumnIndexOrThrow("id")),
        projectId = getLong(getColumnIndexOrThrow("project_id")),
        projectName = getString(getColumnIndexOrThrow("project_name")),
        originalName = getString(getColumnIndexOrThrow("original_name")),
        fileName = getString(getColumnIndexOrThrow("file_name")),
        filePath = getString(getColumnIndexOrThrow("file_path")),
        thumbnailPath = getNullableString("thumbnail_path"),
        type = ImageType.fromDatabase(getString(getColumnIndexOrThrow("type"))),
        status = ImageStatus.fromDatabase(getString(getColumnIndexOrThrow("status"))),
        notes = getString(getColumnIndexOrThrow("notes")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        expiresAt = getNullableLong("expires_at"),
        warningAt = getNullableLong("warning_at"),
        notifiedAt = getNullableLong("notified_at"),
        trashedAt = getNullableLong("trashed_at"),
        deleteAfter = getNullableLong("delete_after"),
        deletedAt = getNullableLong("deleted_at")
    )

    /** Reads a nullable integer timestamp from the current cursor row. */
    private fun Cursor.getNullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    /** Reads a nullable text value from the current cursor row. */
    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        private const val DATABASE_NAME = "cinevault.db"
        private const val DATABASE_VERSION = 1
        private const val BASE_SELECT =
            "SELECT i.*, p.name AS project_name FROM imagenes i JOIN proyectos p ON p.id = i.project_id"
    }
}
