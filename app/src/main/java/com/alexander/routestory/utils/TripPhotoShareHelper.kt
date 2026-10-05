package com.alexander.routestory.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import com.alexander.routestory.domain.Track

/**
 * Sprint 69: gallery save + system share for the HQ trip photo card.
 * MediaStore (scoped storage, no permission on 29+) + FileProvider share file.
 * The filesDir/trip_photos share dir is declared in res/xml/file_paths.xml.
 */
@Singleton
class TripPhotoShareHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun saveAndShare(bitmap: Bitmap, track: Track) {
        val style = "card"
        val name = "routestory_trip_" + track.id + ".jpg"
        runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/RouteStory"
                )
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
        }
        val dir = File(context.filesDir, "trip_photos").apply { mkdirs() }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(5)
            ?.forEach { runCatching { it.delete() } }
        val file = File(dir, "trip_" + track.id + "_" + style + ".jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        val shareUri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, null).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
