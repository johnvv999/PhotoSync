package com.johnvv.photosync

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs every ~15 minutes. Finds camera photos added since the last successful
 * sync, tags each with its GPS-derived city/country, and uploads it into the
 * single flat PhotoSync folder on Drive as "Country_City_NNN.jpg".
 */
class PhotoUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncState = SyncState(applicationContext)
        val accountName = syncState.selectedAccountName
            ?: return@withContext Result.failure() // not signed in yet

        val drive = DriveServiceHelper(applicationContext, accountName)

        val rootFolderId = syncState.rootFolderId ?: run {
            val id = drive.getOrCreateRootFolder()
            syncState.rootFolderId = id
            id
        }

        val indexStore = LocationIndexStore(applicationContext)
        val sinceEpochSeconds = syncState.lastSyncedEpochSeconds
        var maxProcessedEpochSeconds = sinceEpochSeconds

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(sinceEpochSeconds.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        val resolver = applicationContext.contentResolver
        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        ) ?: return@withContext Result.retry()

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val dateAdded = it.getLong(dateCol)
                val contentUri: Uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )

                try {
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

                    maxProcessedEpochSeconds = maxOf(maxProcessedEpochSeconds, dateAdded)
                    // Persist progress after each photo so a mid-batch failure doesn't
                    // cause already-uploaded photos to be re-uploaded on retry.
                    syncState.lastSyncedEpochSeconds = maxProcessedEpochSeconds
                } catch (e: Exception) {
                    // Leave lastSyncedEpochSeconds where it is; this photo (and any
                    // after it) will be retried on the next run.
                    return@withContext Result.retry()
                }
            }
        }

        Result.success()
    }
}
