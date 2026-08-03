package com.johnvv.photosync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PhotoUploadWorker"

/**
 * Uploads photos to the flat PhotoSync Drive folder as "Country_City_NNN.jpg".
 *
 * Runs in one of four modes, chosen via [KEY_MODE]:
 *  - [MODE_AUTO] (default — used by the periodic background job): every camera
 *    photo added since the last successful sync.
 *  - [MODE_ALL]: every photo with DATE_TAKEN inside [KEY_START_EPOCH_MS]..[KEY_END_EPOCH_MS].
 *  - [MODE_CITY]: same date range, filtered to the GPS-derived cities in [KEY_CITY_KEYS].
 *  - [MODE_INDIVIDUAL]: exactly the photo ids in [KEY_PHOTO_IDS].
 */
class PhotoUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_AUTO = "auto"
        const val MODE_ALL = "all"
        const val MODE_CITY = "city"
        const val MODE_INDIVIDUAL = "individual"

        const val KEY_START_EPOCH_MS = "start_epoch_ms"
        const val KEY_END_EPOCH_MS = "end_epoch_ms"
        const val KEY_CITY_KEYS = "city_keys" // comma-separated PhotoLocation.key() values
        const val KEY_PHOTO_IDS = "photo_ids" // comma-separated MediaStore _ID values
        const val KEY_FORCE_DUPLICATES = "force_duplicates" // individual mode: upload even if already synced

        // Progress reported via setProgress so the UI can show "X of Y backed up".
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_TOTAL = "progress_total"

        /**
         * Which half of the run is being reported. Reading every photo's EXIF
         * happens before anything uploads and takes real time on a large library,
         * so without naming the phase the UI would sit blank through it and look
         * hung.
         */
        const val PROGRESS_PHASE = "progress_phase"
        const val PHASE_PREPARING = "preparing"
        const val PHASE_UPLOADING = "uploading"

        /**
         * How many photos this run still had to file as "Unsorted_NoGPS" — no fix
         * of their own and nothing in range to inherit one from. Reported as
         * output data so the UI can confirm the run left none behind, rather than
         * the user having to go and check Drive.
         */
        const val RESULT_UNLOCATED = "result_unlocated"

        /**
         * How many photos actually uploaded. This has to travel in output data
         * rather than being read back off the progress fields: WorkManager clears
         * progress the moment a worker reaches a terminal state, so a completion
         * handler reading [PROGRESS_TOTAL] always sees nothing.
         */
        const val RESULT_UPLOADED = "result_uploaded"

        /**
         * Shared tag on every upload request, so the Sync tab can watch whatever
         * sync is in flight — whichever screen started it — and cancel it by tag
         * rather than having to know its id.
         */
        const val TAG_UPLOAD = "photosync_upload"

        /**
         * Auto-sync never considers photos taken before this date, even if the
         * saved "last synced" mark is 0 (e.g. after a reinstall wiped app data).
         * Without this floor a reset would crawl the user's entire camera roll.
         */
        val AUTO_SYNC_FLOOR_EPOCH_SECONDS: Long = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JULY, 16, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncState = SyncState(applicationContext)
        val accountName = syncState.selectedAccountName
            ?: return@withContext Result.failure() // not signed in yet

        val drive = DriveServiceHelper(applicationContext, accountName)
        val indexStore = LocationIndexStore(applicationContext)
        val uploadedStore = UploadedPhotoStore(applicationContext)
        val excludedStore = ExcludedPhotoStore(applicationContext)
        val rootFolderId = syncState.rootFolderId ?: try {
            val result = drive.getOrCreateRootFolder()
            syncState.rootFolderId = result.id
            if (result.wasCreated) {
                // A brand new Drive folder means any old "already uploaded" marks
                // describe photos in a folder that no longer exists (e.g. the user
                // deleted the previous PhotoSync folder) — they'd otherwise block
                // every real photo from ever being (re-)uploaded to the new one.
                Log.w(TAG, "Root folder recreated, clearing stale upload bookkeeping")
                uploadedStore.clearAll()
            }
            result.id
        } catch (e: Exception) {
            // Transient network/auth hiccup setting up the Drive folder — retry
            // later rather than permanently failing (which Result.failure()
            // would do, requiring the user to manually restart the sync).
            Log.w(TAG, "getOrCreateRootFolder failed, will retry", e)
            return@withContext Result.retry()
        }

        when (inputData.getString(KEY_MODE) ?: MODE_AUTO) {
            MODE_INDIVIDUAL -> {
                val ids = inputData.getString(KEY_PHOTO_IDS).orEmpty()
                    .split(",").filter { it.isNotBlank() }.map { it.toLong() }
                val forceDuplicates = inputData.getBoolean(KEY_FORCE_DUPLICATES, false)
                uploadEntries(syncState, drive, indexStore, uploadedStore, excludedStore, rootFolderId, ids.map { PhotoEntry(it, 0L) }, forceDuplicates)
            }
            MODE_CITY -> {
                val cityKeys = inputData.getString(KEY_CITY_KEYS).orEmpty()
                    .split(",").filter { it.isNotBlank() }.toSet()
                val photos = PhotoScanner.queryPhotos(applicationContext, startEpochMs(), endEpochMs())
                val matching = PhotoScanner.groupByCity(applicationContext, photos)
                    .filter { it.locationKey in cityKeys }
                    .flatMap { it.photos }
                uploadEntries(syncState, drive, indexStore, uploadedStore, excludedStore, rootFolderId, matching)
            }
            MODE_ALL -> {
                val photos = PhotoScanner.queryPhotos(applicationContext, startEpochMs(), endEpochMs())
                uploadEntries(syncState, drive, indexStore, uploadedStore, excludedStore, rootFolderId, photos)
            }
            else -> runAutoSync(syncState, drive, indexStore, uploadedStore, excludedStore, rootFolderId)
        }
    }

    /**
     * The cached root folder ID can go stale if the PhotoSync folder was
     * deleted on Drive after the app already cached its ID — every upload
     * then 404s on the parent forever. Clearing the cache lets the next
     * attempt recreate the folder via getOrCreateRootFolder().
     */
    private fun handleUploadException(syncState: SyncState, id: Long, e: Exception): Result {
        if (e is GoogleJsonResponseException && e.statusCode == 404 && !syncState.usingSharedFolder) {
            // Only auto-recreate folders this app owns. A 404 on a shared folder
            // means access/permission trouble, not a deleted own-folder — silently
            // making a new private folder would split the sync.
            Log.w(TAG, "Upload failed for photo id=$id: root folder missing on Drive, recreating", e)
            syncState.rootFolderId = null
        } else {
            Log.w(TAG, "Upload failed for photo id=$id, will retry", e)
        }
        return Result.retry()
    }

    private fun startEpochMs(): Long? = inputData.getLong(KEY_START_EPOCH_MS, -1L).takeIf { it >= 0 }
    private fun endEpochMs(): Long? = inputData.getLong(KEY_END_EPOCH_MS, -1L).takeIf { it >= 0 }

    private fun uploadEntries(
        syncState: SyncState,
        drive: DriveServiceHelper,
        indexStore: LocationIndexStore,
        uploadedStore: UploadedPhotoStore,
        excludedStore: ExcludedPhotoStore,
        rootFolderId: String,
        entries: List<PhotoEntry>,
        forceDuplicates: Boolean = false
    ): Result {
        val resolver = applicationContext.contentResolver
        val toUpload = entries.filter { entry ->
            !excludedStore.isExcluded(entry.id) && (forceDuplicates || !uploadedStore.isUploaded(entry.id))
        }

        val candidates = resolveLocations(resolver, syncState, toUpload)
        if (isStopped) {
            syncState.recordSyncOutcome(SyncStatus.OUTCOME_STOPPED)
            return Result.failure()
        }

        var done = 0
        reportProgress(PHASE_UPLOADING, done, candidates.size)
        for (candidate in candidates) {
            // Uploading a photo is one long blocking call, so cancellation can't
            // interrupt it — check between photos instead, which makes Stop take
            // effect within one photo rather than at the end of the batch.
            if (isStopped) {
                syncState.recordSyncOutcome(SyncStatus.OUTCOME_STOPPED)
                return Result.failure()
            }
            try {
                uploadOne(resolver, drive, indexStore, uploadedStore, rootFolderId, candidate)
                reportProgress(PHASE_UPLOADING, ++done, candidates.size)
            } catch (e: Exception) {
                return handleUploadException(syncState, candidate.entry.id, e)
            }
        }
        return successWithCounts(syncState, candidates, done)
    }

    /**
     * Reports what the run achieved: how many photos went up, and how many it
     * couldn't place — so the UI can confirm none were left tagged NoGPS.
     */
    private fun successWithCounts(
        syncState: SyncState,
        candidates: List<Candidate>,
        uploaded: Int
    ): Result {
        val unlocated = candidates.count { it.location == null }
        // Persisted as well as returned: output data only reaches a screen that
        // was observing, and a sync that finishes with the app in the background
        // still has to be able to report itself later.
        syncState.recordSyncOutcome(SyncStatus.OUTCOME_SUCCESS, uploaded, unlocated)
        return Result.success(
            androidx.work.workDataOf(RESULT_UPLOADED to uploaded, RESULT_UNLOCATED to unlocated)
        )
    }

    /** One photo about to be uploaded, with the location it should be filed under. */
    private class Candidate(val entry: PhotoEntry, val takenMs: Long, val ownCoords: DoubleArray?) {
        /** Where this photo gets filed — its own place, or the one it inherited. */
        var location: PhotoLocation? = null
        /** Coordinates to stamp into the upload, set only when the photo had none of its own. */
        var coordsToStamp: DoubleArray? = null
    }

    /**
     * Works out a location for every photo in the batch *before* any of them
     * upload, so nothing lands as "Unsorted_NoGPS".
     *
     * A photo without GPS takes the location of the nearest photo in time that
     * has one — looking backwards first (the usual case: you walk indoors and
     * the fix drops), then forwards, which is what rescues photos at the very
     * start of a batch with nothing before them. The chain is seeded from, and
     * written back to, [SyncState.lastKnownLocation] so it survives across runs.
     *
     * Only a batch where no photo anywhere has a fix — and no earlier run
     * recorded one — still produces "Unsorted_NoGPS", because at that point
     * there is genuinely no location in existence to copy.
     */
    private fun resolveLocations(
        resolver: ContentResolver,
        syncState: SyncState,
        entries: List<PhotoEntry>
    ): List<Candidate> {
        val geocodeCache = mutableMapOf<String, PhotoLocation>()

        val scanned = ArrayList<Candidate>(entries.size)
        entries.forEachIndexed { index, entry ->
            // Reading a whole library's EXIF takes minutes, and it all happens
            // before the first upload. Without this check Stop would appear to do
            // nothing for the entire preparation phase.
            if (isStopped) return emptyList()
            val facts = OriginalMedia.open(resolver, entry.contentUri())?.use { stream ->
                LocationNaming.readExifFacts(stream)
            }
            scanned += Candidate(entry, facts?.takenMs ?: entry.dateTakenMs, facts?.coords)
            reportProgress(PHASE_PREPARING, index + 1, entries.size)
        }

        // Ascending capture order, and this sort is load-bearing twice over:
        // inheritance only makes sense chronologically, and the per-location
        // counter is handed out in upload order, so uploading newest-first (which
        // is how MODE_ALL arrives) numbers the whole library backwards.
        val candidates = scanned.sortedBy { it.takenMs }

        fun geocode(coords: DoubleArray): PhotoLocation {
            val key = "%.3f,%.3f".format(coords[0], coords[1])
            return geocodeCache.getOrPut(key) {
                LocationNaming.reverseGeocode(applicationContext, coords[0], coords[1])
            }
        }

        // Backward fill, seeded from wherever the previous run left off.
        var lastLocation: PhotoLocation? = syncState.lastKnownLocation
        var lastCoords: DoubleArray? = syncState.lastKnownCoords
        for (candidate in candidates) {
            val own = candidate.ownCoords
            if (own != null) {
                val located = geocode(own)
                // A photo can have a perfectly good fix that the geocoder simply
                // can't name (offline, or open water). Leaving it on the
                // placeholder would tag it NoGPS despite having coordinates, so
                // treat it as unplaced and let it inherit a name like the rest.
                if (!LocationNaming.isPlaceholder(located)) {
                    lastLocation = located
                    lastCoords = own
                    candidate.location = located
                }
            }
            if (candidate.location == null && lastLocation != null) {
                candidate.location = lastLocation
                // Only photos with no fix of their own need one stamped in; one
                // whose geocode merely failed already carries real coordinates.
                if (own == null) candidate.coordsToStamp = lastCoords
            }
        }

        // Forward fill for anything still unplaced — photos before the batch's
        // first fix, which the backward pass can't reach.
        var nextLocation: PhotoLocation? = null
        var nextCoords: DoubleArray? = null
        for (candidate in candidates.asReversed()) {
            val own = candidate.ownCoords
            if (own != null) {
                val located = geocode(own)
                if (!LocationNaming.isPlaceholder(located)) {
                    nextLocation = located
                    nextCoords = own
                }
            }
            if (candidate.location == null && nextLocation != null) {
                candidate.location = nextLocation
                if (own == null) candidate.coordsToStamp = nextCoords
            }
        }

        if (lastLocation != null) {
            syncState.lastKnownLocation = lastLocation
            syncState.lastKnownCoords = lastCoords
        }
        val unplaced = candidates.count { it.location == null }
        if (unplaced > 0) {
            Log.w(TAG, "$unplaced photo(s) have no GPS anywhere in range — filing as Unsorted_NoGPS")
        }
        return candidates
    }

    private fun reportProgress(phase: String, done: Int, total: Int) {
        setProgressAsync(
            androidx.work.workDataOf(
                PROGRESS_PHASE to phase,
                PROGRESS_DONE to done,
                PROGRESS_TOTAL to total
            )
        )
    }

    private fun uploadOne(
        resolver: ContentResolver,
        drive: DriveServiceHelper,
        indexStore: LocationIndexStore,
        uploadedStore: UploadedPhotoStore,
        rootFolderId: String,
        candidate: Candidate
    ) {
        val contentUri = candidate.entry.contentUri()
        val location = candidate.location ?: PhotoLocation(city = "NoGPS", country = "Unsorted")
        val index = indexStore.nextIndex(location.key())
        val fileName = LocationNaming.buildFileName(location, index)

        val stamp = candidate.coordsToStamp
        if (stamp == null) {
            // The photo carries its own fix already — upload the original bytes
            // untouched. OriginalMedia is what keeps that fix from being stripped
            // in transit; a redacted upload loses it for good, since the Drive
            // copy is then the only one the Edit screen can ever see.
            OriginalMedia.open(resolver, contentUri)?.use { uploadStream ->
                drive.uploadPhoto(uploadStream, fileName, rootFolderId, candidate.takenMs)
            }
        } else {
            uploadWithStampedGps(
                resolver, drive, contentUri, fileName, rootFolderId,
                candidate.entry.id, stamp, candidate.takenMs
            )
        }
        uploadedStore.markUploaded(candidate.entry.id)
    }

    /**
     * Uploads a photo that inherited its location, with those coordinates written
     * into its EXIF so the location travels with the file rather than living only
     * in its name. Needs a real file on disk — ExifInterface can't rewrite a
     * stream — so the bytes go through the cache directory on the way.
     */
    private fun uploadWithStampedGps(
        resolver: ContentResolver,
        drive: DriveServiceHelper,
        contentUri: Uri,
        fileName: String,
        rootFolderId: String,
        photoId: Long,
        coords: DoubleArray,
        takenTimeMs: Long
    ) {
        val temp = java.io.File(applicationContext.cacheDir, "photosync_stamp_$photoId.jpg")
        try {
            OriginalMedia.open(resolver, contentUri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return
            // Best effort: if the format can't take an EXIF rewrite the photo
            // still uploads, just without a fix of its own.
            LocationNaming.writeLatLong(temp, coords[0], coords[1])
            temp.inputStream().use { drive.uploadPhoto(it, fileName, rootFolderId, takenTimeMs) }
        } finally {
            temp.delete()
        }
    }

    private fun runAutoSync(
        syncState: SyncState,
        drive: DriveServiceHelper,
        indexStore: LocationIndexStore,
        uploadedStore: UploadedPhotoStore,
        excludedStore: ExcludedPhotoStore,
        rootFolderId: String
    ): Result {
        // Clamp forward to the floor so a reset/zeroed mark can't drag the whole
        // historical camera roll into the upload set.
        val sinceEpochSeconds = maxOf(syncState.lastSyncedEpochSeconds, AUTO_SYNC_FLOOR_EPOCH_SECONDS)
        if (sinceEpochSeconds != syncState.lastSyncedEpochSeconds) {
            syncState.lastSyncedEpochSeconds = sinceEpochSeconds
        }
        val maxProcessedEpochSeconds = sinceEpochSeconds

        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(sinceEpochSeconds.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        val resolver = applicationContext.contentResolver
        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        ) ?: return Result.retry()

        // Snapshot the candidate rows so we know the total up front (for progress).
        val candidates = mutableListOf<PhotoEntry>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (it.moveToNext()) {
                candidates += PhotoEntry(it.getLong(idCol), it.getLong(dateCol) * 1000L)
            }
        }

        val toUpload = candidates.filter {
            !uploadedStore.isUploaded(it.id) && !excludedStore.isExcluded(it.id)
        }
        val resolved = resolveLocations(resolver, syncState, toUpload)
        if (isStopped) {
            syncState.recordSyncOutcome(SyncStatus.OUTCOME_STOPPED)
            return Result.failure()
        }

        var done = 0
        reportProgress(PHASE_UPLOADING, done, resolved.size)
        for (candidate in resolved) {
            // Stop between photos — see uploadEntries. Leaving the mark unmoved on
            // the way out means the next run picks up where this one stopped.
            if (isStopped) {
                syncState.recordSyncOutcome(SyncStatus.OUTCOME_STOPPED)
                return Result.failure()
            }
            try {
                uploadOne(resolver, drive, indexStore, uploadedStore, rootFolderId, candidate)
                reportProgress(PHASE_UPLOADING, ++done, resolved.size)
            } catch (e: Exception) {
                // Leave the mark where it is and retry the run. Uploads now go in
                // capture order rather than date-added order, so the mark can't be
                // advanced photo-by-photo without risking stepping over something
                // that hasn't uploaded yet — and uploadedStore already stops
                // anything that did land from going up a second time.
                return handleUploadException(syncState, candidate.entry.id, e)
            }
        }

        // The batch finished, so move the mark past everything this run looked at,
        // skipped and excluded photos included.
        val newestSeen = candidates.maxOfOrNull { it.dateTakenMs / 1000L } ?: maxProcessedEpochSeconds
        syncState.lastSyncedEpochSeconds = maxOf(maxProcessedEpochSeconds, newestSeen)

        return successWithCounts(syncState, resolved, done)
    }
}
