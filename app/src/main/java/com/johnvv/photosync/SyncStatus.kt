package com.johnvv.photosync

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.work.WorkManager

/**
 * One place that decides what a sync's state looks like in words, shared by the
 * Sync tab and the Sync Options screen so the two can't drift apart.
 *
 * Everything is derived from work tagged [PhotoUploadWorker.TAG_UPLOAD] plus the
 * outcome the worker persisted, not from a listener attached when the sync
 * started. That's what lets a screen opened *during* a background sync show live
 * progress, and a screen opened *after* one finished still report how it went.
 */
object SyncStatus {

    const val OUTCOME_SUCCESS = "success"
    const val OUTCOME_STOPPED = "stopped"
    const val OUTCOME_FAILED = "failed"

    /** What to display, and whether a sync is currently in flight. */
    data class Snapshot(val text: String, val running: Boolean)

    /**
     * Calls [onUpdate] whenever the sync picture changes, for as long as
     * [owner] is alive. [onUpdate] receives null when there is nothing to say —
     * no sync running and none ever recorded — leaving the caller free to show
     * whatever that screen shows when idle.
     */
    fun watch(context: Context, owner: LifecycleOwner, onUpdate: (Snapshot?) -> Unit) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext)
            .getWorkInfosByTagLiveData(PhotoUploadWorker.TAG_UPLOAD)
            .observe(owner) { infos ->
                val running = infos.orEmpty().firstOrNull { !it.state.isFinished }
                if (running != null) {
                    val done = running.progress.getInt(PhotoUploadWorker.PROGRESS_DONE, -1)
                    val total = running.progress.getInt(PhotoUploadWorker.PROGRESS_TOTAL, -1)
                    val phase = running.progress.getString(PhotoUploadWorker.PROGRESS_PHASE)
                    val text = when {
                        total < 0 -> appContext.getString(R.string.sync_started_status)
                        phase == PhotoUploadWorker.PHASE_PREPARING ->
                            appContext.getString(R.string.preparing_progress, done, total)
                        else -> appContext.getString(R.string.backing_up_progress, done, total)
                    }
                    onUpdate(Snapshot(text, running = true))
                } else {
                    onUpdate(lastOutcome(appContext)?.let { Snapshot(it, running = false) })
                }
            }
    }

    /** The recorded result of the most recent sync, or null if none has finished yet. */
    fun lastOutcome(context: Context): String? {
        val syncState = SyncState(context)
        return when (syncState.lastSyncOutcome) {
            OUTCOME_STOPPED -> context.getString(R.string.sync_stopped)
            OUTCOME_FAILED -> context.getString(R.string.sync_failed_status)
            OUTCOME_SUCCESS -> {
                val uploaded = syncState.lastSyncUploaded
                val unlocated = syncState.lastSyncUnlocated
                when {
                    uploaded <= 0 -> context.getString(R.string.nothing_to_back_up)
                    unlocated > 0 -> context.getString(R.string.backed_up_with_nogps, uploaded, unlocated)
                    else -> context.getString(R.string.backed_up_all_located, uploaded)
                }
            }
            else -> null
        }
    }
}
