package com.vidal.cinevault.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.vidal.cinevault.R
import com.vidal.cinevault.core.AppConfig
import com.vidal.cinevault.core.LifecyclePolicy
import com.vidal.cinevault.core.ScreenshotManager
import com.vidal.cinevault.core.SyncFolderManager
import com.vidal.cinevault.databinding.ActivityDetailBinding
import com.vidal.cinevault.model.ImageRecord
import com.vidal.cinevault.model.ImageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat

/** Full-screen inspection, sharing, restoration and permanent actions. */
class DetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private lateinit var manager: ScreenshotManager
    private lateinit var policy: LifecyclePolicy
    private lateinit var syncFolderManager: SyncFolderManager
    private lateinit var imageId: String
    private var current: ImageRecord? = null

    /** Wires the full-screen view and loads the requested stable image ID. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val config = AppConfig.load(this)
        manager = ScreenshotManager(this, config)
        policy = LifecyclePolicy(config)
        syncFolderManager = SyncFolderManager(this)
        imageId = intent.getStringExtra(EXTRA_IMAGE_ID) ?: run { finish(); return }
        binding.detailToolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
        binding.detailToolbar.setNavigationOnClickListener { finish() }
        binding.permanentButton.setOnClickListener { makePermanent() }
        binding.restoreButton.setOnClickListener { restore() }
        binding.shareButton.setOnClickListener { share() }
        load()
    }

    /** Loads metadata and a sampled preview without blocking the UI thread. */
    private fun load() {
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val record = manager.getImage(imageId)
                record to record?.let { decodeSampled(it.filePath, 1600, 1600) }
            }
            val record = pair.first ?: run { finish(); return@launch }
            current = record
            binding.fullImage.setImageBitmap(pair.second)
            binding.detailProject.text = record.projectName
            binding.detailBadge.text = badgeText(record)
            binding.detailBadge.setBackgroundResource(
                when (record.status) {
                    ImageStatus.PERMANENT -> R.drawable.bg_badge_permanent
                    ImageStatus.TRASH, ImageStatus.DELETED -> R.drawable.bg_badge_trash
                    ImageStatus.TEMP -> R.drawable.bg_badge_temp
                }
            )
            val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            binding.detailMetadata.text = buildString {
                appendLine("Tipo: ${record.type.displayName}")
                appendLine("Añadida: ${formatter.format(record.createdAt)}")
                record.expiresAt?.let { appendLine("Pasa a papelera: ${formatter.format(it)}") }
                record.deleteAfter?.let { appendLine("Borrado definitivo: ${formatter.format(it)}") }
                appendLine("Resolución: ${record.width} × ${record.height}")
                appendLine("Tamaño: ${formatBytes(record.sizeBytes)}")
                appendLine("Archivo original: ${record.originalName}")
                if (record.notes.isNotBlank()) append("\nNotas\n${record.notes}")
            }
            binding.permanentButton.visibility = if (record.status == ImageStatus.PERMANENT) View.GONE else View.VISIBLE
            binding.restoreButton.visibility = if (record.status == ImageStatus.TRASH) View.VISIBLE else View.GONE
            binding.shareButton.isEnabled = record.status != ImageStatus.DELETED && File(record.filePath).isFile
        }
    }

    /** Protects the current file and mirrors it when an external folder is linked. */
    private fun makePermanent() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    manager.markPermanent(imageId).also {
                        if (syncFolderManager.isConfigured()) runCatching { syncFolderManager.mirror(it) }
                    }
                }
            }
            Toast.makeText(
                this@DetailActivity,
                result.fold({ "Guardada como permanente" }, { it.message ?: "No se pudo guardar" }),
                Toast.LENGTH_LONG
            ).show()
            if (result.isSuccess) load()
        }
    }

    /** Restores a trash item with a fresh full retention window. */
    private fun restore() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { manager.restore(imageId) } }
            Toast.makeText(
                this@DetailActivity,
                result.fold({ "Restaurada con 48 h nuevas" }, { it.message ?: "No se pudo restaurar" }),
                Toast.LENGTH_LONG
            ).show()
            if (result.isSuccess) load()
        }
    }

    /** Shares a read-only content URI without exposing raw filesystem paths. */
    private fun share() {
        val record = current ?: return
        val file = File(record.filePath)
        if (!file.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = record.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Compartir imagen"
            )
        )
    }

    /** Formats the current lifecycle state for the detail badge. */
    private fun badgeText(record: ImageRecord): String {
        val now = System.currentTimeMillis()
        return when (record.status) {
            ImageStatus.PERMANENT -> "📌 Permanente"
            ImageStatus.TEMP -> "⏳ ${record.expiresAt?.let { policy.hoursLeft(it, now) } ?: 0} h restantes"
            ImageStatus.TRASH -> "🗑 ${record.deleteAfter?.let { policy.hoursLeft(it, now) } ?: 0} h de gracia"
            ImageStatus.DELETED -> "Eliminada"
        }
    }

    /** Downsamples the full image for safe detail-screen rendering. */
    private fun decodeSampled(path: String, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > requestedWidth * 2 || bounds.outHeight / sample > requestedHeight * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Converts a byte count into a short human-readable label. */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    companion object {
        const val EXTRA_IMAGE_ID = "image_id"
    }
}
