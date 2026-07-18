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
}
