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

    /**
     * Budgets, as a share of the heap this app is allowed.
     *
     * These caches used to be bounded by entry count — 64 thumbnails, 16 sets
     * of bytes — which says nothing about memory when the entries are photos.
     * Sixty-four decoded 12MP bitmaps is about 3GB against a heap of a few
     * hundred megabytes, so a long enough list ran out of memory as a matter of
     * arithmetic rather than bad luck.
     */
    private val heapBudget = Runtime.getRuntime().maxMemory()
    private val thumbnailBudget = (heapBudget / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val byteBudget = (heapBudget / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Undecoded JPEGs, measured in bytes rather than counted. */
    private val bytes = object : LruCache<String, ByteArray>(byteBudget) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private val thumbnails = object : LruCache<String, Bitmap>(thumbnailBudget) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Synchronised because deleting clears entries from a background thread
    // while the list on screen is reading them. LruCache handles its own
    // locking; a plain HashMap does not, and the failure mode of one being read
    // and written at once is corruption rather than an honest error.
    private val descriptions =
        java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    /** fileId -> GPS: absent means not resolved yet, null means the photo has none. */
    private val gps =
        java.util.Collections.synchronizedMap(mutableMapOf<String, DoubleArray?>())

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
