package com.johnvv.photosync

import android.content.Context

/**
 * Tracks MediaStore photo ids that have already been uploaded, so re-running
 * any sync mode (auto, all, by city, individual) never creates a second Drive
 * copy of the same photo.
 */
class UploadedPhotoStore(context: Context) {

    private val prefs = context.getSharedPreferences("photosync_uploaded", Context.MODE_PRIVATE)

    fun isUploaded(photoId: Long): Boolean = prefs.getBoolean(photoId.toString(), false)

    fun markUploaded(photoId: Long) {
        prefs.edit().putBoolean(photoId.toString(), true).apply()
    }
}
