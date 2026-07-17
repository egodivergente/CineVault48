package com.vidal.cinevault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vidal.cinevault.core.AppConfig
import com.vidal.cinevault.core.Clock
import com.vidal.cinevault.core.ScreenshotManager
import com.vidal.cinevault.data.CineVaultDatabase
import com.vidal.cinevault.model.ImageMetadata
import com.vidal.cinevault.model.ImageStatus
import com.vidal.cinevault.model.ImageType
import com.vidal.cinevault.model.SearchFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Device/emulator integration suite for files, SQLite and every lifecycle method. */
@RunWith(AndroidJUnit4::class)
class ScreenshotManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: CineVaultDatabase
    private lateinit var manager: ScreenshotManager
    private lateinit var root: File
    private lateinit var sourceDirectory: File
    private lateinit var databaseName: String
    private lateinit var clock: MutableClock
    private val hour = 60L * 60L * 1000L
    private val config = AppConfig(
        rootFolder = "test",
        retentionHours = 48,
        warningHoursBeforeExpiry = 24,
        trashGraceHours = 24,
        checkIntervalHours = 1,
        thumbnailSizePx = 128,
        maxImportBatch = 30,
        notificationsEnabled = true,
        dryRun = false
    )

    /** Creates an isolated database, clock and managed directory per test. */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        databaseName = "cinevault-test-$suffix.db"
        root = File(context.cacheDir, "managed-$suffix")
        sourceDirectory = File(context.cacheDir, "sources-$suffix")
        database = CineVaultDatabase(context, databaseName)
        clock = MutableClock(1_800_000_000_000L)
        manager = ScreenshotManager(context, config, database, clock, root) { false }
    }

    /** Closes SQLite and removes only the isolated test paths. */
    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
        sourceDirectory.deleteRecursively()
    }

    /** Verifies import, thumbnail creation, deadlines and logging. */
    @Test
    fun addImageCopiesFileCreatesThumbnailAndStartsTimer() {
        val source = TestImageFactory.create(sourceDirectory, "frame.png")
        val image = manager.addImage(
            source.absolutePath,
            ImageMetadata("Anthem", ImageType.FRAME, "Plano inicial", "frame.png", "image/png")
        )

        assertEquals(ImageStatus.TEMP, image.status)
        assertEquals(clock.nowMillis() + 48L * hour, image.expiresAt)
        assertEquals(clock.nowMillis() + 24L * hour, image.warningAt)
        assertTrue(File(image.filePath).isFile)
        assertTrue(File(requireNotNull(image.thumbnailPath)).isFile)
        assertTrue(source.isFile)
        assertEquals(1, database.countLogs("IMAGE_ADDED"))
    }

    /** Verifies that making an item permanent cancels every deadline. */
    @Test
    fun markPermanentMovesFileAndCancelsDeadlines() {
        val image = addOne()
        val permanent = manager.markPermanent(image.id)

        assertEquals(ImageStatus.PERMANENT, permanent.status)
        assertTrue(permanent.filePath.contains("permanent"))
        assertTrue(File(permanent.filePath).isFile)
        assertFalse(File(image.filePath).exists())
        assertEquals(null, permanent.expiresAt)
    }

    /** Verifies the complete temporary-to-trash-to-deleted sequence. */
    @Test
    fun checkExpiredMoveTrashDeleteExpiredCompletesLifecycle() {
        val image = addOne()
        clock.advanceHours(49)
        assertEquals(listOf(image.id), manager.checkExpired().map { it.id })

        val trashReport = manager.moveExpiredToTrash()
        assertEquals(1, trashReport.movedToTrash.size)
        val trashed = requireNotNull(manager.getImage(image.id))
        assertEquals(ImageStatus.TRASH, trashed.status)
        assertTrue(File(trashed.filePath).isFile)

        clock.advanceHours(25)
        assertEquals(1, manager.checkTrashExpired().size)
        val deleteReport = manager.deleteExpired()
        assertEquals(1, deleteReport.deleted.size)
        val deleted = requireNotNull(manager.getImage(image.id))
        assertEquals(ImageStatus.DELETED, deleted.status)
        assertFalse(File(trashed.filePath).exists())
        assertEquals(1, database.countLogs("DELETED"))
    }

    /** Verifies restoration and its fresh 48-hour timer. */
    @Test
    fun restoreReturnsTrashItemToTempWithFreshWindow() {
        val image = addOne()
        clock.advanceHours(49)
        manager.moveExpiredToTrash()
        val restoredAt = clock.nowMillis()
        val restored = manager.restore(image.id)

        assertEquals(ImageStatus.TEMP, restored.status)
        assertEquals(restoredAt + 48L * hour, restored.expiresAt)
        assertTrue(restored.filePath.contains("temp"))
    }

    /** Verifies explicit confirmed deletion from the trash. */
    @Test
    fun deleteNowRemovesSelectedTrashItem() {
        val image = addOne()
        clock.advanceHours(49)
        manager.moveExpiredToTrash()
        val trashPath = requireNotNull(manager.getImage(image.id)).filePath

        assertTrue(manager.deleteNow(image.id))
        assertEquals(ImageStatus.DELETED, manager.getImage(image.id)?.status)
        assertFalse(File(trashPath).exists())
        assertEquals(1, database.countLogs("DELETED_MANUALLY"))
    }

    /** Verifies that simulation reports without mutating files or state. */
    @Test
    fun dryRunReportsWithoutMovingOrDeleting() {
        val image = addOne()
        clock.advanceHours(49)
        val dryManager = ScreenshotManager(context, config, database, clock, root) { true }
        val report = dryManager.moveExpiredToTrash()

        assertEquals(listOf(image.id), report.simulatedTrashMoves.map { it.id })
        assertEquals(ImageStatus.TEMP, dryManager.getImage(image.id)?.status)
        assertTrue(File(image.filePath).isFile)
        assertEquals(1, database.countLogs("DRY_RUN_TRASH"))

        manager.moveExpiredToTrash()
        clock.advanceHours(25)
        val deleteReport = dryManager.deleteExpired()
        assertEquals(listOf(image.id), deleteReport.simulatedDeletes.map { it.id })
        assertEquals(ImageStatus.TRASH, dryManager.getImage(image.id)?.status)
        assertEquals(1, database.countLogs("DRY_RUN_DELETE"))
    }

    /** Verifies project, date and type query filters. */
    @Test
    fun searchFiltersProjectDateAndType() {
        val sourceA = TestImageFactory.create(sourceDirectory, "a.png", 1)
        manager.addImage(sourceA.path, ImageMetadata("Anthem", ImageType.PROMPT, originalName = "a.png"))
        clock.advanceHours(2)
        val boundary = clock.nowMillis()
        val sourceB = TestImageFactory.create(sourceDirectory, "b.png", 2)
        manager.addImage(sourceB.path, ImageMetadata("Metro", ImageType.REFERENCE, originalName = "b.png"))

        assertEquals(1, manager.search(SearchFilter(project = "Anthem", type = ImageType.PROMPT)).size)
        assertEquals(1, manager.search(SearchFilter(dateFrom = boundary)).size)
        assertEquals(1, manager.search(SearchFilter(type = ImageType.REFERENCE)).size)
    }

    /** Verifies one-time eligibility for the preventive notification. */
    @Test
    fun notificationCandidateAppearsOnceWarningWindowStarts() {
        val image = addOne()
        assertTrue(manager.notificationCandidates().isEmpty())
        clock.advanceHours(24)
        assertEquals(listOf(image.id), manager.notificationCandidates().map { it.id })
        manager.markWarningsNotified(listOf(image.id))
        assertTrue(manager.notificationCandidates().isEmpty())
    }

    /** Generates the requested ten records with simulated timestamps. */
    @Test
    fun tenImagesWithSimulatedTimestampsRemainSearchable() {
        repeat(10) { index ->
            val source = TestImageFactory.create(sourceDirectory, "fixture-$index.png", index)
            manager.addImage(
                source.path,
                ImageMetadata("Proyecto ${index % 3}", ImageType.entries[index % 3], originalName = source.name)
            )
            clock.advanceHours(1)
        }
        val results = manager.search()
        assertEquals(10, results.size)
        assertNotNull(results.first().expiresAt)
        assertTrue(results.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt })
    }

    /** Imports one reusable PNG fixture. */
    private fun addOne() = manager.addImage(
        TestImageFactory.create(sourceDirectory, "one-${UUID.randomUUID()}.png").path,
        ImageMetadata("Test", ImageType.FRAME, originalName = "one.png", mimeType = "image/png")
    )

    private class MutableClock(private var value: Long) : Clock {
        /** Returns the deterministic test instant. */
        override fun nowMillis(): Long = value
        /** Advances the deterministic clock without sleeping. */
        fun advanceHours(hours: Long) { value += hours * 60L * 60L * 1000L }
    }
}
