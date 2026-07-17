package com.vidal.cinevault.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vidal.cinevault.R
import com.vidal.cinevault.core.AppConfig
import com.vidal.cinevault.core.ScreenshotManager
import com.vidal.cinevault.core.SyncFolderManager
import com.vidal.cinevault.core.UriImporter
import com.vidal.cinevault.databinding.ActivityMainBinding
import com.vidal.cinevault.databinding.DialogFiltersBinding
import com.vidal.cinevault.databinding.DialogImportBinding
import com.vidal.cinevault.model.ImageMetadata
import com.vidal.cinevault.model.ImageRecord
import com.vidal.cinevault.model.ImageStatus
import com.vidal.cinevault.model.ImageType
import com.vidal.cinevault.model.SearchFilter
import com.vidal.cinevault.work.MaintenanceScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main S25 Ultra dashboard: grid, filters, import, selection and trash controls. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private lateinit var manager: ScreenshotManager
    private lateinit var syncFolderManager: SyncFolderManager
    private lateinit var adapter: ImageAdapter
    private val selectedIds = linkedSetOf<String>()
    private var visibleImages: List<ImageRecord> = emptyList()
    private var inTrash = false
    private var filterState = FilterState()
    private var pendingMetadata = ImageMetadata()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val syncFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { syncFolderManager.setFolder(uri) }
                .onSuccess { mirrorExistingPermanentImages() }
                .onFailure { Toast.makeText(this, it.message ?: "No se pudo vincular la carpeta", Toast.LENGTH_LONG).show() }
        }
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    /** Initializes the native dashboard, launchers, worker and saved intent state. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        config = AppConfig.load(this)
        manager = ScreenshotManager(this, config)
        syncFolderManager = SyncFolderManager(this)
        adapter = ImageAdapter(
            config,
            onClick = ::openDetail,
            onLongClick = { toggleSelection(it.id) },
            onChecked = { image, checked -> setSelected(image.id, checked) }
        )
        binding.imageGrid.layoutManager = GridLayoutManager(this, 2)
        binding.imageGrid.adapter = adapter
        bindActions()
        requestNotificationPermissionIfNeeded()
        if (intent.getBooleanExtra(EXTRA_SHOW_EXPIRING, false)) {
            filterState = filterState.copy(expiringOnly = true)
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_TRASH, false)) inTrash = true
        refresh()
    }

    /** Refreshes after returning from detail or Android settings. */
    override fun onResume() {
        super.onResume()
        if (::manager.isInitialized) refresh()
    }

    /** Routes notification taps to expiring images or the trash view. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_EXPIRING, false)) {
            inTrash = false
            filterState = filterState.copy(expiringOnly = true)
            refresh()
        } else if (intent.getBooleanExtra(EXTRA_SHOW_TRASH, false)) {
            inTrash = true
            filterState = filterState.copy(expiringOnly = false)
            refresh()
        }
    }

    /** Connects toolbar and selection actions to lifecycle operations. */
    private fun bindActions() {
        binding.galleryButton.setOnClickListener {
            inTrash = false
            filterState = filterState.copy(expiringOnly = false)
            clearSelection()
            refresh()
        }
        binding.trashButton.setOnClickListener {
            inTrash = true
            clearSelection()
            refresh()
        }
        binding.addButton.setOnClickListener { showImportDialog() }
        binding.filterButton.setOnClickListener { showFilterDialog() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.primarySelectionAction.setOnClickListener { runPrimarySelectionAction() }
        binding.secondarySelectionAction.setOnClickListener { confirmDeleteSelected() }
    }

    /** Loads the current filter on a background dispatcher and refreshes the grid. */
    private fun refresh() {
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            val records = withContext(Dispatchers.IO) {
                val from = filterState.datePreset.days?.let { days ->
                    System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
                }
                val statuses = if (inTrash) setOf(ImageStatus.TRASH)
                else setOf(ImageStatus.TEMP, ImageStatus.PERMANENT)
                var results = manager.search(
                    SearchFilter(
                        project = filterState.project,
                        dateFrom = from,
                        type = filterState.type,
                        statuses = statuses
                    )
                )
                if (filterState.expiringOnly && !inTrash) {
                    val limit = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
                    results = results.filter { it.status == ImageStatus.TEMP && (it.expiresAt ?: Long.MAX_VALUE) <= limit }
                }
                results
            }
            visibleImages = records
            selectedIds.retainAll(records.map { it.id }.toSet())
            adapter.submit(records, selectedIds)
            binding.progress.visibility = View.GONE
            binding.emptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyText.text = getString(if (inTrash) R.string.empty_trash else R.string.empty_gallery)
            updateHeader()
            updateSelectionBar()
        }
    }

    /** Renders current screen, result count, active filters and dry-run state. */
    private fun updateHeader() {
        binding.galleryButton.isEnabled = inTrash
        binding.trashButton.isEnabled = !inTrash
        binding.addButton.visibility = if (inTrash) View.GONE else View.VISIBLE
        val mode = if (inTrash) "Papelera · gracia de 24 h" else "Galería activa"
        val filterLabel = buildList {
            filterState.project?.takeIf { it.isNotBlank() }?.let { add("proyecto: $it") }
            filterState.type?.let { add(it.displayName) }
            if (filterState.datePreset != DatePreset.ALL) add(filterState.datePreset.displayName)
            if (filterState.expiringOnly) add("caducan en 24 h")
        }.joinToString(" · ")
        val dryRun = if (AppConfig.load(this).dryRun) " · SIMULACIÓN" else ""
        binding.statusText.text = "$mode · ${visibleImages.size} imágenes$dryRun" +
            if (filterLabel.isBlank()) "" else "\n$filterLabel"
    }

    /** Collects metadata before launching Android's private Photo Picker. */
    private fun showImportDialog() {
        val dialogBinding = DialogImportBinding.inflate(layoutInflater)
        dialogBinding.typeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ImageType.entries.map { it.displayName }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Añadir a la galería temporal")
            .setMessage("El contador de 48 h empieza al importar.")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Elegir imágenes") { _, _ ->
                val project = dialogBinding.projectInput.text?.toString().orEmpty().ifBlank { "Sin proyecto" }
                pendingMetadata = ImageMetadata(
                    project = project,
                    type = ImageType.entries[dialogBinding.typeSpinner.selectedItemPosition],
                    notes = dialogBinding.notesInput.text?.toString().orEmpty()
                )
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            .show()
    }

    /** Stages and imports selected URIs sequentially on an IO dispatcher. */
    private fun importUris(uris: List<android.net.Uri>) {
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            binding.statusText.text = "Importando ${uris.size} imágenes…"
            var imported = 0
            val failures = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                val importer = UriImporter(this@MainActivity)
                for (uri in uris.take(config.maxImportBatch)) {
                    var staged: UriImporter.StagedImage? = null
                    try {
                        staged = importer.stage(uri)
                        manager.addImage(
                            staged.file.absolutePath,
                            pendingMetadata.copy(
                                originalName = staged.displayName,
                                mimeType = staged.mimeType
                            )
                        )
                        imported++
                    } catch (error: Exception) {
                        failures += error.message ?: "Error desconocido"
                    } finally {
                        staged?.file?.delete()
                    }
                }
            }
            MaintenanceScheduler.runNow(this@MainActivity)
            val message = if (failures.isEmpty()) "$imported imágenes añadidas"
            else "$imported añadidas · ${failures.size} errores"
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    /** Displays project, type and date filters with apply and reset actions. */
    private fun showFilterDialog() {
        val dialogBinding = DialogFiltersBinding.inflate(layoutInflater)
        dialogBinding.projectFilterInput.setText(filterState.project.orEmpty())
        val types = listOf("Todos los tipos") + ImageType.entries.map { it.displayName }
        dialogBinding.typeFilterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        dialogBinding.typeFilterSpinner.setSelection(filterState.type?.let { ImageType.entries.indexOf(it) + 1 } ?: 0)
        dialogBinding.dateFilterSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DatePreset.entries.map { it.displayName }
        )
        dialogBinding.dateFilterSpinner.setSelection(DatePreset.entries.indexOf(filterState.datePreset))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Filtrar galería")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Limpiar", null)
            .setPositiveButton("Aplicar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typePosition = dialogBinding.typeFilterSpinner.selectedItemPosition
                val project = dialogBinding.projectFilterInput.text?.toString()?.trim()
                filterState = FilterState(
                    project = project?.takeIf { it.isNotBlank() },
                    type = if (typePosition == 0) null else ImageType.entries[typePosition - 1],
                    datePreset = DatePreset.entries[dialogBinding.dateFilterSpinner.selectedItemPosition]
                )
                clearSelection()
                dialog.dismiss()
                refresh()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                filterState = FilterState()
                clearSelection()
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    /** Edits dry-run, notifications and optional external backup folder. */
    private fun showSettingsDialog() {
        val current = AppConfig.load(this)
        val selected = booleanArrayOf(current.dryRun, current.notificationsEnabled)
        MaterialAlertDialogBuilder(this)
            .setTitle("Ajustes de seguridad")
            .setMultiChoiceItems(
                arrayOf("Modo simulación: no mover ni borrar", "Notificaciones 24 h antes"),
                selected
            ) { _, which, checked -> selected[which] = checked }
            .setMessage(
                "Los tiempos base se editan en assets/config.json antes de compilar.\n\n" +
                    "Copia externa: ${if (syncFolderManager.isConfigured()) "vinculada" else "sin configurar"}"
            )
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Carpeta de copia") { _, _ -> syncFolderPicker.launch(null) }
            .setPositiveButton("Guardar") { _, _ ->
                AppConfig.setDryRun(this, selected[0])
                AppConfig.setNotificationsEnabled(this, selected[1])
                if (selected[1]) requestNotificationPermissionIfNeeded()
                MaintenanceScheduler.schedule(this)
                refresh()
            }
            .show()
    }

    /** Toggles selection after a long press. */
    private fun toggleSelection(imageId: String) = setSelected(imageId, imageId !in selectedIds)

    /** Adds or removes one ID and redraws selection affordances. */
    private fun setSelected(imageId: String, selected: Boolean) {
        if (selected) selectedIds += imageId else selectedIds -= imageId
        adapter.updateSelection(selectedIds)
        updateSelectionBar()
    }

    /** Exits selection mode and clears all checkboxes. */
    private fun clearSelection() {
        selectedIds.clear()
        if (::adapter.isInitialized) adapter.updateSelection(selectedIds)
        if (::binding.isInitialized) updateSelectionBar()
    }

    /** Adapts bulk actions to gallery or trash context. */
    private fun updateSelectionBar() {
        binding.selectionBar.visibility = if (selectedIds.isEmpty()) View.GONE else View.VISIBLE
        binding.selectionCount.text = "${selectedIds.size} seleccionadas"
        binding.primarySelectionAction.text = if (inTrash) getString(R.string.restore) else getString(R.string.mark_permanent)
        binding.secondarySelectionAction.visibility = if (inTrash && selectedIds.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Makes gallery items permanent or restores selected trash items. */
    private fun runPrimarySelectionAction() {
        val ids = selectedIds.toList()
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            val failures = withContext(Dispatchers.IO) {
                ids.mapNotNull { id ->
                    runCatching {
                        if (inTrash) manager.restore(id) else manager.markPermanent(id).also {
                            if (syncFolderManager.isConfigured()) runCatching { syncFolderManager.mirror(it) }
                        }
                    }.exceptionOrNull()?.message
                }
            }
            clearSelection()
            Toast.makeText(
                this@MainActivity,
                if (failures.isEmpty()) "Cambios guardados" else "${failures.size} operaciones fallaron",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    /** Requires explicit confirmation before immediate physical deletion. */
    private fun confirmDeleteSelected() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar definitivamente?")
            .setMessage("Esta acción elimina los archivos seleccionados ahora. En modo simulación solo se registrará.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                val ids = selectedIds.toList()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ids.forEach { runCatching { manager.deleteNow(it) } } }
                    clearSelection()
                    refresh()
                }
            }
            .show()
    }

    /** Opens a full-screen record using its stable UUID. */
    private fun openDetail(image: ImageRecord) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_IMAGE_ID, image.id))
    }

    /** Requests Android 13+ notification permission only when necessary. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Mirrors every existing permanent item after the user selects a backup folder. */
    private fun mirrorExistingPermanentImages() {
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                val permanents = manager.search(SearchFilter(statuses = setOf(ImageStatus.PERMANENT)))
                val copied = permanents.count { runCatching { syncFolderManager.mirror(it) }.getOrDefault(false) }
                copied to permanents.size
            }
            Toast.makeText(
                this@MainActivity,
                "Carpeta vinculada · ${result.first}/${result.second} permanentes copiadas",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    companion object {
        const val EXTRA_SHOW_EXPIRING = "show_expiring"
        const val EXTRA_SHOW_TRASH = "show_trash"
    }
}
