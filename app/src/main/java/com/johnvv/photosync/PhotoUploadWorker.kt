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
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncState = SyncState(applicationContext)
        val accountName = syncState.selectedAccountName
            ?: return@withContext Result.failure() // not signed in yet

        val drive = DriveServiceHelper(applicationContext, accountName)
        val indexStore = LocationIndexStore(applicationContext)
        val uploadedStore = UploadedPhotoStore(applicationContext)
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
                uploadEntries(syncState, drive, indexStore, uploadedStore, rootFolderId, ids.map { PhotoEntry(it, 0L) })
            }
            MODE_CITY -> {
                val cityKeys = inputData.getString(KEY_CITY_KEYS).orEmpty()
                    .split(",").filter { it.isNotBlank() }.toSet()
                val photos = PhotoScanner.queryPhotos(applicationContext, startEpochMs(), endEpochMs())
                val matching = PhotoScanner.groupByCity(applicationContext, photos)
                    .filter { it.locationKey in cityKeys }
                    .flatMap { it.photos }
                uploadEntries(syncState, drive, indexStore, uploadedStore, rootFolderId, matching)
            }
            MODE_ALL -> {
                val photos = PhotoScanner.queryPhotos(applicationContext, startEpochMs(), endEpochMs())
                uploadEntries(syncState, drive, indexStore, uploadedStore, rootFolderId, photos)
            }
            else -> runAutoSync(syncState, drive, indexStore, uploadedStore, rootFolderId)
        }
    }

    /**
     * The cached root folder ID can go stale if the PhotoSync folder was
     * deleted on Drive after the app already cached its ID — every upload
     * then 404s on the parent forever. Clearing the cache lets the next
     * attempt recreate the folder via getOrCreateRootFolder().
     */
    private fun handleUploadException(syncState: SyncState, id: Long, e: Exception): Result {
        if (e is GoogleJsonResponseException && e.statusCode == 404) {
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
        rootFolderId: String,
        entries: List<PhotoEntry>
    ): Result {
        val resolver = applicationContext.contentResolver
        for (entry in entries) {
            if (uploadedStore.isUploaded(entry.id)) continue
            try {
                uploadOne(resolver, drive, indexStore, uploadedStore, rootFolderId, entry.id, entry.contentUri())
            } catch (e: Exception) {
                return handleUploadException(syncState, entry.id, e)
            }
        }
        return Result.success()
    }

    private fun uploadOne(
        resolver: ContentResolver,
        drive: DriveServiceHelper,
        indexStore: LocationIndexStore,
        uploadedStore: UploadedPhotoStore,
        rootFolderId: String,
        photoId: Long,
        contentUri: Uri
    ) {
        val location = resolver.openInputStream(contentUri)?.use { exifStream ->
            LocationNaming.readLatLong(exifStream)
        }?.let { latLong ->
            LocationNaming.reverseGeocode(applicationContext, latLong[0], latLong[1])
        } ?: PhotoLocation(city = "NoGPS", country = "Unsorted")

        val index = indexStore.nextIndex(location.key())
        val fileName = LocationNaming.buildFileName(location, index)

        resolver.openInputStream(contentUri)?.use { uploadStream ->
            drive.uploadPhoto(uploadStream, fileName, rootFolderId)
        }
        uploadedStore.markUploaded(photoId)
    }

    private fun runAutoSync(
        syncState: SyncState,
        drive: DriveServiceHelper,
        indexStore: LocationIndexStore,
        uploadedStore: UploadedPhotoStore,
        rootFolderId: String
    ): Result {
        val sinceEpochSeconds = syncState.lastSyncedEpochSeconds
        var maxProcessedEpochSeconds = sinceEpochSeconds

        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(sinceEpochSeconds.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        val resolver = applicationContext.contentResolver
        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        ) ?: return Result.retry()

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val dateAdded = it.getLong(dateCol)
                val contentUri: Uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )

                if (uploadedStore.isUploaded(id)) {
                    // Already uploaded via a manual sync mode — just advance the cursor past it.
                    maxProcessedEpochSeconds = maxOf(maxProcessedEpochSeconds, dateAdded)
                    syncState.lastSyncedEpochSeconds = maxProcessedEpochSeconds
                    continue
                }

                try {
                    uploadOne(resolver, drive, indexStore, uploadedStore, rootFolderId, id, contentUri)
                    maxProcessedEpochSeconds = maxOf(maxProcessedEpochSeconds, dateAdded)
                    // Persist progress after each photo so a mid-batch failure doesn't
                    // cause already-uploaded photos to be re-uploaded on retry.
                    syncState.lastSyncedEpochSeconds = maxProcessedEpochSeconds
                } catch (e: Exception) {
                    // Leave lastSyncedEpochSeconds where it is; this photo (and any
                    // after it) will be retried on the next run.
                    return handleUploadException(syncState, id, e)
                }
            }
        }

        return Result.success()
    }
}
