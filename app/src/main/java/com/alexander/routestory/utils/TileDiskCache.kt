package com.alexander.routestory.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Shared on-disk tile cache: one helper for live OSM tiles and video snapshot
 * tiles. Namespaces ("live2", "sat") keep imagery sources from colliding;
 * total size is capped with best-effort LRU pruning.
 */
object TileDiskCache {
    private const val MAX_FILES = 2000

    fun file(context: Context, namespace: String, z: Int, x: Int, y: Int): File =
        File(context.filesDir, "map_tiles/$namespace/$z/$x/$y.png")

    fun load(file: File): Bitmap? {
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun store(file: File, bitmap: Bitmap) {
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            prune(file.parentFile?.parentFile?.parentFile?.parentFile)
        }
    }

    private fun prune(root: File?) {
        if (root == null || !root.exists()) return
        runCatching {
            val files = root.walkTopDown().filter { it.isFile }.toList()
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_FILES)
                .forEach { runCatching { it.delete() } }
        }
    }
}
