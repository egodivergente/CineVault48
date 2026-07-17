package com.vidal.cinevault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vidal.cinevault.R
import com.vidal.cinevault.core.AppConfig
import com.vidal.cinevault.core.LifecyclePolicy
import com.vidal.cinevault.databinding.ItemImageBinding
import com.vidal.cinevault.model.ImageRecord
import com.vidal.cinevault.model.ImageStatus
import java.io.File
import java.util.concurrent.Executors

/** Two-column thumbnail adapter with long-press multi-selection. */
class ImageAdapter(
    config: AppConfig,
    private val onClick: (ImageRecord) -> Unit,
    private val onLongClick: (ImageRecord) -> Unit,
    private val onChecked: (ImageRecord, Boolean) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {
    private val policy = LifecyclePolicy(config)
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        /** Reports bitmap memory in KiB for the 32 MiB cache budget. */
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private var images: List<ImageRecord> = emptyList()
    private var selectedIds: Set<String> = emptySet()

    /** Replaces the visible result set and current selection snapshot. */
    fun submit(images: List<ImageRecord>, selectedIds: Set<String>) {
        this.images = images
        this.selectedIds = selectedIds.toSet()
        notifyDataSetChanged()
    }

    /** Updates selection markers without changing the underlying result order. */
    fun updateSelection(selectedIds: Set<String>) {
        this.selectedIds = selectedIds.toSet()
        notifyDataSetChanged()
    }

    /** Inflates one Material thumbnail card. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ImageViewHolder(binding)
    }

    /** Binds the record at the requested grid position. */
    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) = holder.bind(images[position])

    /** Returns the number of records currently displayed. */
    override fun getItemCount(): Int = images.size

    /** Holds and binds one cinematic thumbnail card. */
    inner class ImageViewHolder(private val binding: ItemImageBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Populates labels, selection state, badge and asynchronous thumbnail. */
        fun bind(image: ImageRecord) {
            val selected = image.id in selectedIds
            val selectionMode = selectedIds.isNotEmpty()
            binding.projectText.text = image.projectName
            binding.typeText.text = image.type.displayName
            binding.checkbox.setOnCheckedChangeListener(null)
            binding.checkbox.isChecked = selected
            binding.checkbox.visibility = if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.checkbox.setOnCheckedChangeListener { _, checked -> onChecked(image, checked) }
            binding.root.strokeColor = ContextCompat.getColor(
                binding.root.context,
                if (selected) R.color.violet else R.color.surface_high
            )
            binding.root.strokeWidth = if (selected) 3 else 1
            bindBadge(image)
            bindThumbnail(image)
            binding.root.setOnClickListener {
                if (selectedIds.isNotEmpty()) onChecked(image, image.id !in selectedIds) else onClick(image)
            }
            binding.root.setOnLongClickListener {
                onLongClick(image)
                true
            }
        }

        /** Formats lifecycle state as a compact mobile badge. */
        private fun bindBadge(image: ImageRecord) {
            val now = System.currentTimeMillis()
            when (image.status) {
                ImageStatus.PERMANENT -> {
                    binding.badge.text = "📌 Permanente"
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_permanent)
                }
                ImageStatus.TRASH -> {
                    val hours = image.deleteAfter?.let { policy.hoursLeft(it, now) } ?: 0L
                    binding.badge.text = "🗑 ${hours}h"
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_trash)
                }
                ImageStatus.TEMP -> {
                    val hours = image.expiresAt?.let { policy.hoursLeft(it, now) } ?: 0L
                    binding.badge.text = "⏳ ${hours}h"
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_temp)
                }
                ImageStatus.DELETED -> {
                    binding.badge.text = "Eliminada"
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_trash)
                }
            }
        }

        /** Resolves a cached preview or decodes it outside the UI thread. */
        private fun bindThumbnail(image: ImageRecord) {
            val path = image.thumbnailPath?.takeIf { File(it).isFile } ?: image.filePath
            binding.thumbnail.tag = image.id
            val cached = cache.get(path)
            if (cached != null && !cached.isRecycled) {
                binding.thumbnail.setImageBitmap(cached)
                return
            }
            binding.thumbnail.setImageDrawable(null)
            executor.execute {
                val bitmap = decodeSampled(path, 720, 480)
                if (bitmap != null) cache.put(path, bitmap)
                mainHandler.post {
                    if (binding.thumbnail.tag == image.id) binding.thumbnail.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** Downsamples a bitmap to prevent large screenshots exhausting phone memory. */
    private fun decodeSampled(path: String, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > requestedWidth * 2 || bounds.outHeight / sample > requestedHeight * 2) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
