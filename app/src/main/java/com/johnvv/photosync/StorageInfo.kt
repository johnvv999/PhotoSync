package com.johnvv.photosync

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.util.Locale

/** Device storage and on-device photo-usage stats for the main screen. */
object StorageInfo {

    data class Stats(val photoCount: Int, val photoBytes: Long, val freeBytes: Long, val totalBytes: Long)

    fun read(context: Context): Stats {
        val stat = StatFs(Environment.getDataDirectory().path)
        val free = stat.availableBytes
        val total = stat.totalBytes

        var count = 0
        var bytes = 0L
        val projection = arrayOf(MediaStore.Images.Media.SIZE)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
        )?.use { cursor ->
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                count++
                bytes += cursor.getLong(sizeCol)
            }
        }
        return Stats(count, bytes, free, total)
    }

    /** e.g. "1.4 GB". */
    fun formatBytes(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
        val mb = bytes / 1_000_000.0
        return String.format(Locale.US, "%.0f MB", mb)
    }
}
