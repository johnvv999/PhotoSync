package com.johnvv.photosync

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Process-level caches for photos pulled out of Drive.
 *
 * Kept outside any one adapter so bytes, decoded thumbnails and Gemini
 * descriptions are shared between the browse and edit screens, and survive an
 * activity being recreated (returning from the fullscreen view, or re-running
 * a scan) instead of re-downloading the whole folder each time.
 */
object DrivePhotoCache {

    private val bytes = LruCache<String, ByteArray>(16)
    private val thumbnails = LruCache<String, Bitmap>(64)
    private val descriptions = mutableMapOf<String, String>()

    /** fileId -> GPS: absent means not resolved yet, null means the photo has none. */
    private val gps = mutableMapOf<String, DoubleArray?>()

    fun bytes(drive: DriveServiceHelper, fileId: String): ByteArray? {
        bytes.get(fileId)?.let { return it }
        return try {
            drive.downloadPhotoBytes(fileId).also { bytes.put(fileId, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun thumbnail(fileId: String): Bitmap? = thumbnails.get(fileId)

    fun putThumbnail(fileId: String, bitmap: Bitmap) = thumbnails.put(fileId, bitmap)

    fun description(fileId: String): String? = descriptions[fileId]

    fun putDescription(fileId: String, text: String) {
        descriptions[fileId] = text
    }

    fun hasGps(fileId: String): Boolean = gps.containsKey(fileId)

    fun gps(fileId: String): DoubleArray? = gps[fileId]

    fun putGps(fileId: String, coords: DoubleArray?) {
        gps[fileId] = coords
    }

    /** Drops everything held for [fileId] — call after deleting it from Drive. */
    fun forget(fileId: String) {
        bytes.remove(fileId)
        thumbnails.remove(fileId)
        descriptions.remove(fileId)
        gps.remove(fileId)
    }

    /**
     * Drops the cached description for [fileId] without touching its pixels.
     * Renaming a photo changes what the app knows about where it was taken,
     * and the description mentions the location — the bytes are unchanged, so
     * only the text needs re-fetching.
     */
    fun forgetDescription(fileId: String) {
        descriptions.remove(fileId)
    }
}
