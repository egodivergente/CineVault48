package com.vidal.cinevault

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File

/** Creates tiny deterministic PNG fixtures without bundling binary test assets. */
object TestImageFactory {
    /** Writes a colored PNG suitable for import tests. */
    fun create(directory: File, name: String, index: Int = 0): File {
        directory.mkdirs()
        val file = File(directory, name)
        val bitmap = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.CYAN)
        bitmap.eraseColor(colors[index % colors.size])
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
