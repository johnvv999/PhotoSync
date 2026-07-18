package com.johnvv.photosync

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/** Shared on-device thumbnail loading for the photo grid and synced-photo list. */
object PhotoThumbnails {
    fun load(context: Context, photo: PhotoEntry): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(photo.contentUri(), Size(200, 200), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver, photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null
            )
        }
    } catch (e: Exception) {
        null
    }
}
