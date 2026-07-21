package com.johnvv.photosync

import android.content.Context

/**
 * Tracks MediaStore photo ids the user has flagged to never upload. Every sync
 * mode skips these, so an excluded photo will never appear in the Drive folder
 * no matter how the sync is triggered.
 */
class ExcludedPhotoStore(context: Context) {

    private val prefs = context.getSharedPreferences("photosync_excluded", Context.MODE_PRIVATE)

    fun isExcluded(photoId: Long): Boolean = prefs.getBoolean(photoId.toString(), false)

    fun setExcluded(photoId: Long, excluded: Boolean) {
        prefs.edit().apply {
            if (excluded) putBoolean(photoId.toString(), true) else remove(photoId.toString())
        }.apply()
    }
}
