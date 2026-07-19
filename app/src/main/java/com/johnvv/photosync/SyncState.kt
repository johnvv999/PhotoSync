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
}
