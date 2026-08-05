package com.johnvv.photosync

import android.content.Context
import android.util.LruCache
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Thin wrapper around the Drive REST API v3.
 *
 * Uses two scopes: drive.file (write access, needed to create the PhotoSync
 * folder and upload photos into it) plus drive.readonly (read access to the
 * whole Drive, needed so "Browse Synced Photos" can see photos added to the
 * folder some other way, e.g. directly via drive.google.com — drive.file
 * alone only lets the app see files it created itself).
 */
class DriveServiceHelper(context: Context, accountName: String) {

    companion object {
        const val ROOT_FOLDER_NAME = "PhotoSync"
        const val MIME_FOLDER = "application/vnd.google-apps.folder"

        /**
         * Drive appProperties keys used to persist the Edit screen's chronological
         * ordering on Drive itself, so the order survives reinstalls and is visible
         * to any other client reading the folder — Drive has no user-defined file
         * ordering of its own, so the sequence has to live in per-file metadata.
         */
        const val PROP_ORDER = "photosyncOrder"
        const val PROP_TAKEN_MS = "photosyncTakenMs"

        /** EXIF timestamps come back from Drive as "yyyy:MM:dd HH:mm:ss" in the camera's local time. */
        private val EXIF_TIME_FORMAT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        private fun parseExifTime(raw: String?): Long? = raw?.let {
            try {
                synchronized(EXIF_TIME_FORMAT) { EXIF_TIME_FORMAT.parse(it) }?.time
            } catch (e: Exception) {
                null
            }
        }
    }

    private val appContext = context.applicationContext
    private val service: Drive
    /**
     * Recently downloaded photos, measured in bytes.
     *
     * It was bounded at 32 entries, which for photos means 32 files of whatever
     * size they happen to be — well over a hundred megabytes of a heap capped
     * at 256, and that is per helper: every screen builds its own. Two screens
     * open was the whole heap spoken for before anything drew.
     *
     * Kept small because [DrivePhotoCache] already holds a process-wide byte
     * cache over the same downloads; this one only spares an immediate repeat.
     */
    private val downloadCache = object : LruCache<String, ByteArray>(
        (Runtime.getRuntime().maxMemory() / 32).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_READONLY)
        )
        credential.selectedAccountName = accountName

        service = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("PhotoSync").build()
    }

    /**
     * Finds the PhotoSync root folder, creating it if it doesn't exist yet.
     * [RootFolderResult.wasCreated] tells the caller whether this is a brand
     * new, empty folder (as opposed to an existing one being reused) — that
     * distinction matters for deciding whether old "already uploaded"
     * bookkeeping is still valid.
     */
    fun getOrCreateRootFolder(): RootFolderResult {
        val query = "mimeType='$MIME_FOLDER' and name='$ROOT_FOLDER_NAME' " +
            "and trashed=false and 'root' in parents"
        val existing = service.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        existing.files?.firstOrNull()?.let { return RootFolderResult(it.id, wasCreated = false) }

        val folderMetadata = DriveFile().apply {
            name = ROOT_FOLDER_NAME
            mimeType = MIME_FOLDER
        }
        val created = service.files().create(folderMetadata)
            .setFields("id")
            .execute()
        return RootFolderResult(created.id, wasCreated = true)
    }

    /**
     * Uploads [inputStream] as [fileName] directly into [parentFolderId]. Returns the new file ID.
     *
     * [takenTimeMs] backdates the Drive file to when the photo was actually
     * taken. Without it every upload is stamped with the time it reached Drive,
     * so a whole library synced in one go shows up dated today — in the Drive
     * UI, in the public browsing page (which sorts on createdTime), and anywhere
     * else reading file dates rather than EXIF. Both timestamps are writable on
     * create, and this is the only chance to set createdTime.
     */
    fun uploadPhoto(
        inputStream: InputStream,
        fileName: String,
        parentFolderId: String,
        takenTimeMs: Long? = null
    ): String {
        val metadata = DriveFile().apply {
            name = fileName
            parents = listOf(parentFolderId)
            takenTimeMs?.takeIf { it > 0 }?.let {
                val taken = DateTime(it)
                createdTime = taken
                modifiedTime = taken
            }
        }
        val content = com.google.api.client.http.InputStreamContent("image/jpeg", inputStream)
        val uploaded = service.files().create(metadata, content)
            .setFields("id")
            .execute()
        return uploaded.id
    }

    /**
     * Lists the image files directly inside [folderId], oldest first. This must
     * stay cheap — it does NOT download any photo bytes, so it returns fast even
     * for large folders. City labels come from the app's "Country_City_NNN"
     * filename convention.
     *
     * Drive already parsed each photo's EXIF for us, so `imageMediaMetadata`
     * hands back both the capture time and the GPS fix for free in this same
     * listing call. The capture time — not Drive's createdTime, which is merely
     * when the photo was uploaded — is what "chronological" has to mean here,
     * since photos are frequently synced long after they were taken and in
     * whatever order the camera roll happened to be walked.
     */
    fun listPhotosInFolder(folderId: String): List<DrivePhoto> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val result = service.files().list()
                .setQ("'$folderId' in parents and trashed=false and mimeType contains 'image/'")
                .setSpaces("drive")
                .setPageSize(1000)
                .setPageToken(pageToken)
                .setFields(
                    "nextPageToken, files(id, name, description, mimeType, createdTime, " +
                        "appProperties, imageMediaMetadata(time, location))"
                )
                .execute()
            files += result.files.orEmpty()
            pageToken = result.nextPageToken
        } while (pageToken != null)

        return files
            .map { file ->
                val createdMs = file.createdTime?.value ?: 0L
                val meta = file.imageMediaMetadata
                // (0, 0) is a camera writing a blank GPS tag, not a real fix in the
                // Atlantic — same guard LocationNaming applies when reading EXIF.
                val location = meta?.location
                    ?.takeIf { it.latitude != null && it.longitude != null }
                    ?.takeUnless { it.latitude == 0.0 && it.longitude == 0.0 }
                DrivePhoto(
                    fileId = file.id,
                    name = file.name,
                    description = file.description,
                    createdTimeMs = createdMs,
                    cityLabel = cityLabelFromName(file.name),
                    lat = location?.latitude,
                    lon = location?.longitude,
                    takenTimeMs = parseExifTime(meta?.time),
                    orderIndex = file.appProperties?.get(PROP_ORDER)?.toIntOrNull()
                )
            }
            // Capture time first, falling back to upload time for photos whose EXIF
            // Drive couldn't read; the saved order only breaks ties, since it is
            // itself derived from this same chronology.
            .sortedWith(compareBy({ it.chronoTimeMs }, { it.orderIndex ?: Int.MAX_VALUE }, { it.name }))
    }

    /**
     * City label from the app's "Country_City_NNN.ext" upload convention, or a
     * generic fallback.
     *
     * "Unsorted_NoGPS_001.jpg" falls back too: those tokens mean the uploader
     * couldn't place the photo, so rendering them as "NoGPS, Unsorted" grouped
     * every unlocated photo under a heading that read like a real city.
     */
    private fun cityLabelFromName(fileName: String): String {
        val parsed = LocationNaming.parseFileName(fileName) ?: return "Other Photos"
        if (LocationNaming.isPlaceholder(parsed.location)) return "Other Photos"
        return LocationNaming.displayLabel(parsed.location)
    }

    /**
     * Renames [fileId] and/or stamps its place in the chronological order onto
     * the file's Drive metadata. Both go in one update call so re-organising a
     * folder costs a single request per photo.
     *
     * Note this only works for files the app itself uploaded: the app holds
     * drive.file (write) plus drive.readonly (read), so a photo added to the
     * folder some other way can be listed but not modified — Drive answers 403
     * for those, which callers surface as a skipped-file count.
     */
    fun updatePhotoNameAndOrder(fileId: String, newName: String?, orderIndex: Int?, takenTimeMs: Long?) {
        val metadata = DriveFile()
        newName?.let { metadata.name = it }
        if (orderIndex != null) {
            metadata.appProperties = buildMap {
                put(PROP_ORDER, orderIndex.toString())
                takenTimeMs?.let { put(PROP_TAKEN_MS, it.toString()) }
            }
        }
        // Backdate photos that were uploaded before the capture time was carried
        // across, so they stop reading as "today". Only modifiedTime can be
        // rewritten after the fact — createdTime is fixed at upload — so a photo
        // already on Drive gets its date corrected in the places that use
        // modifiedTime, and fully corrected only by re-uploading.
        takenTimeMs?.takeIf { it > 0 }?.let { metadata.modifiedTime = DateTime(it) }
        service.files().update(fileId, metadata).setFields("id").execute()
    }

    /**
     * How many images are in [folderId].
     *
     * Asks for ids alone, so a folder of several hundred costs a couple of small
     * metadata pages rather than the full listing [listPhotosInFolder] fetches.
     */
    fun countPhotosInFolder(folderId: String): Int {
        var count = 0
        var pageToken: String? = null
        do {
            val result = service.files().list()
                .setQ("'$folderId' in parents and trashed=false and mimeType contains 'image/'")
                .setSpaces("drive")
                .setPageSize(1000)
                .setPageToken(pageToken)
                .setFields("nextPageToken, files(id)")
                .execute()
            count += result.files.orEmpty().size
            pageToken = result.nextPageToken
        } while (pageToken != null)
        return count
    }

    /**
     * Stores [text] as the file's Drive description.
     *
     * Drive gives every file a description field of up to 4096 characters, which
     * is ample for a few sentences and — unlike appProperties, capped at 124
     * bytes an entry — needs no splitting. Putting it there means a photo is
     * described once and then read by anything with access, including the public
     * browsing page, instead of every viewer paying for the same answer.
     *
     * files.update patches only the fields it is given, so this leaves the name,
     * timestamps and appProperties alone.
     */
    fun setDescription(fileId: String, text: String) {
        service.files()
            .update(fileId, DriveFile().apply { description = text })
            .setFields("id")
            .execute()
    }

    /**
     * Replaces the bytes of an existing [fileId], keeping its id, name, parents
     * and createdTime. Used to write a location into a photo whose EXIF has none.
     *
     * Distinct from [updatePhotoNameAndOrder], which sends metadata only and so
     * cannot alter a photo's pixels or EXIF — renaming must never disturb what a
     * photo says about itself.
     */
    fun updatePhotoContent(fileId: String, inputStream: InputStream, takenTimeMs: Long?) {
        val metadata = DriveFile()
        // Without this the rewrite would stamp the file as modified today.
        takenTimeMs?.takeIf { it > 0 }?.let { metadata.modifiedTime = DateTime(it) }
        val content = com.google.api.client.http.InputStreamContent("image/jpeg", inputStream)
        service.files().update(fileId, metadata, content).setFields("id").execute()
        downloadCache.remove(fileId)
    }

    /**
     * Moves [fileId] to Drive's trash rather than destroying it. A mis-flagged
     * "redundant" photo is then still recoverable from drive.google.com for the
     * usual 30 days, while disappearing from the PhotoSync folder immediately —
     * which is what deleting from this screen is meant to accomplish.
     */
    fun trashFile(fileId: String) {
        service.files().update(fileId, DriveFile().apply { trashed = true })
            .setFields("id")
            .execute()
        downloadCache.remove(fileId)
    }

    /**
     * Reads just the GPS coordinates from a photo's EXIF, downloading only the
     * small header prefix. Used by the adapter to lazily light up the Map link
     * without blocking the initial folder listing.
     */
    fun readGpsCoords(fileId: String): DoubleArray? = try {
        val bytes = downloadPhotoPrefix(fileId)
        ByteArrayInputStream(bytes).use { LocationNaming.readLatLong(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * The small preview JPEG cameras embed in a photo's EXIF header, or null if
     * this one has none.
     *
     * Read from the same ~128 KB prefix used for GPS, so getting it costs a
     * fraction of the full photo. That matters for the duplicate scan, which
     * would otherwise download every photo in the folder in full just to
     * compute a 64-bit hash from a 9x8 version of it.
     */
    fun readEmbeddedThumbnail(fileId: String): ByteArray? = try {
        val prefix = downloadPhotoPrefix(fileId)
        ByteArrayInputStream(prefix).use {
            androidx.exifinterface.media.ExifInterface(it).thumbnailBytes
        }
    } catch (e: Exception) {
        null
    }

    /** Downloads the raw bytes of [fileId]. */
    fun downloadPhotoBytes(fileId: String): ByteArray {
        downloadCache.get(fileId)?.let { return it }
        val bytes = service.files().get(fileId).executeMediaAsInputStream().use { it.readBytes() }
        downloadCache.put(fileId, bytes)
        return bytes
    }

    /** Downloads just enough of [fileId]'s start to read its EXIF header cheaply, without pulling the full image. */
    private fun downloadPhotoPrefix(fileId: String, byteCount: Int = 131072): ByteArray {
        downloadCache.get(fileId)?.let { return it }
        val get = service.files().get(fileId)
        get.requestHeaders.range = "bytes=0-${byteCount - 1}"
        return get.executeMediaAsInputStream().use { it.readBytes() }
    }
}

/** Result of [DriveServiceHelper.getOrCreateRootFolder]. */
data class RootFolderResult(val id: String, val wasCreated: Boolean)

/** A single image file listed from a Drive folder. */
data class DrivePhoto(
    val fileId: String,
    val name: String,
    /** The AI description stored on the Drive file, or null if it has none yet. */
    val description: String? = null,
    val createdTimeMs: Long,
    val cityLabel: String,
    val lat: Double? = null,
    val lon: Double? = null,
    /** EXIF capture time, when Drive could read one. */
    val takenTimeMs: Long? = null,
    /** Position in the chronological order previously saved to Drive, if any. */
    val orderIndex: Int? = null
) {
    /** When the photo was taken, falling back to when it landed on Drive. */
    val chronoTimeMs: Long get() = takenTimeMs ?: createdTimeMs
}
