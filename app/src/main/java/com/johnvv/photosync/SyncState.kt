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
     * True when [rootFolderId] points at a folder shared from another account,
     * rather than one this app created. In that case the app must never
     * auto-create or recreate a folder (e.g. on a transient error) — doing so
     * would silently split the sync back into a private folder.
     */
    var usingSharedFolder: Boolean
        get() = prefs.getBoolean("using_shared_folder", false)
        set(value) = prefs.edit().putBoolean("using_shared_folder", value).apply()
}
