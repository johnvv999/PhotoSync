package com.johnvv.photosync

import android.content.Context

/** Persists the last MediaStore DATE_ADDED (epoch seconds) we've already uploaded through. */
class SyncState(context: Context) {

    private val prefs = context.getSharedPreferences("photosync_state", Context.MODE_PRIVATE)

    var lastSyncedEpochSeconds: Long
        get() = prefs.getLong("last_synced", 0L)
        set(value) = prefs.edit().putLong("last_synced", value).apply()

    var selectedAccountName: String?
        get() = prefs.getString("account_name", null)
        set(value) = prefs.edit().putString("account_name", value).apply()

    var rootFolderId: String?
        get() = prefs.getString("root_folder_id", null)
        set(value) = prefs.edit().putString("root_folder_id", value).apply()

    /**
     * Whether the account has been confirmed via Android's account-chooser dialog.
     * GoogleAccountCredential needs that explicit picker flow (not just a matching
     * email string) to get real AccountManager visibility into a Google account —
     * without it, token requests fail with an opaque null-Account crash.
     */
    var driveAccountAuthorized: Boolean
        get() = prefs.getBoolean("drive_account_authorized", false)
        set(value) = prefs.edit().putBoolean("drive_account_authorized", value).apply()

    /** One-time guard so the app cancels any leftover runaway sync work exactly once. */
    var runawaySyncCleared: Boolean
        get() = prefs.getBoolean("runaway_sync_cleared", false)
        set(value) = prefs.edit().putBoolean("runaway_sync_cleared", value).apply()

    /**
     * The last location the uploader actually resolved from a photo's GPS, kept
     * so a photo without GPS at the *start* of a later batch can still inherit
     * from the real place seen at the end of the previous one. Without this the
     * inheritance chain would reset every time a sync run finished.
     */
    var lastKnownLocation: PhotoLocation?
        get() {
            val city = prefs.getString("last_known_city", null) ?: return null
            val country = prefs.getString("last_known_country", null) ?: return null
            return PhotoLocation(city, country)
        }
        set(value) = prefs.edit()
            .putString("last_known_city", value?.city)
            .putString("last_known_country", value?.country)
            .apply()

    /** Coordinates behind [lastKnownLocation], stamped into inheriting photos so they carry a fix too. */
    var lastKnownCoords: DoubleArray?
        get() {
            if (!prefs.contains("last_known_lat")) return null
            val lat = prefs.getFloat("last_known_lat", 0f).toDouble()
            val lon = prefs.getFloat("last_known_lon", 0f).toDouble()
            return doubleArrayOf(lat, lon)
        }
        set(value) = prefs.edit().apply {
            if (value == null) {
                remove("last_known_lat"); remove("last_known_lon")
            } else {
                putFloat("last_known_lat", value[0].toFloat())
                putFloat("last_known_lon", value[1].toFloat())
            }
        }.apply()

    /**
     * How the last sync ended, one of [SyncStatus.OUTCOME_SUCCESS],
     * [SyncStatus.OUTCOME_STOPPED] or [SyncStatus.OUTCOME_FAILED].
     *
     * Recorded by the worker itself rather than by whichever screen happened to
     * be watching, so a sync that finishes — or is stopped — while the app is in
     * the background still has its result to show when a screen comes back.
     */
    var lastSyncOutcome: String?
        get() = prefs.getString("last_sync_outcome", null)
        set(value) = prefs.edit().putString("last_sync_outcome", value).apply()

    /** Photos uploaded by the last completed sync. */
    var lastSyncUploaded: Int
        get() = prefs.getInt("last_sync_uploaded", 0)
        set(value) = prefs.edit().putInt("last_sync_uploaded", value).apply()

    /** Photos the last sync couldn't place, and so had to tag NoGPS. */
    var lastSyncUnlocated: Int
        get() = prefs.getInt("last_sync_unlocated", 0)
        set(value) = prefs.edit().putInt("last_sync_unlocated", value).apply()

    /** Records the outcome of a finished sync in one shot. */
    fun recordSyncOutcome(outcome: String, uploaded: Int = 0, unlocated: Int = 0) {
        prefs.edit()
            .putString("last_sync_outcome", outcome)
            .putInt("last_sync_uploaded", uploaded)
            .putInt("last_sync_unlocated", unlocated)
            .apply()
    }

    /**
     * Forgets everything that records what has already gone to Drive, so the
     * whole library uploads again from scratch under the current naming rules.
     * Paired with clearing [UploadedPhotoStore] and [LocationIndexStore].
     */
    fun resetForFullReupload() {
        lastSyncedEpochSeconds = PhotoUploadWorker.AUTO_SYNC_FLOOR_EPOCH_SECONDS
        lastKnownLocation = null
        lastKnownCoords = null
    }

    /**
     * True when [rootFolderId] points at a folder shared from another account,
     * rather than one this app created. In that case the app must never
     * auto-create or recreate a folder (e.g. on a transient error) — doing so
     * would silently split the sync back into a private folder.
     */
    var usingSharedFolder: Boolean
        get() = prefs.getBoolean("using_shared_folder", false)
        set(value) = prefs.edit().putBoolean("using_shared_folder", value).apply()
}
