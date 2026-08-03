package com.johnvv.photosync

import android.content.Context

/**
 * Tracks the next available index for each "Country_City" key so uploaded
 * filenames come out as France_Paris_001.jpg, France_Paris_002.jpg, etc.
 *
 * Kept locally (SharedPreferences) rather than queried from Drive on every
 * upload, so two near-simultaneous uploads to the same city don't race
 * against the network and end up with duplicate indices.
 */
class LocationIndexStore(context: Context) {

    private val prefs = context.getSharedPreferences("photosync_indices", Context.MODE_PRIVATE)

    /** Returns the next index for [locationKey] (e.g. "France_Paris") and persists the increment. */
    @Synchronized
    fun nextIndex(locationKey: String): Int {
        val current = prefs.getInt(locationKey, 0)
        val next = current + 1
        prefs.edit().putInt(locationKey, next).apply()
        return next
    }

    /**
     * Pushes the high-water mark for [locationKey] up to [index] if it isn't
     * already there. The Edit screen renumbers existing Drive files from
     * scratch, which can hand out indices above whatever this store had
     * reached (a city that gained photos through inheriting a neighbour's
     * location, say) — without raising the mark, the next upload would reuse
     * an index that's now taken.
     */
    @Synchronized
    fun raiseTo(locationKey: String, index: Int) {
        if (prefs.getInt(locationKey, 0) < index) {
            prefs.edit().putInt(locationKey, index).apply()
        }
    }

    /**
     * Restarts every location's numbering at 001. Only correct when the Drive
     * folder is being repopulated from scratch — otherwise the next upload
     * reuses indices that existing files already hold.
     */
    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
