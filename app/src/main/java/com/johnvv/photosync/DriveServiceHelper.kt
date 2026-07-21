package com.johnvv.photosync

import android.content.Context
import android.util.LruCache
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.ByteArrayInputStream
import java.io.InputStream

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
    }

    private val appContext = context.applicationContext
    private val service: Drive
    private val downloadCache = LruCache<String, ByteArray>(32)

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

    /** Uploads [inputStream] as [fileName] directly into [parentFolderId]. Returns the new file ID. */
    fun uploadPhoto(inputStream: InputStream, fileName: String, parentFolderId: String): String {
        val metadata = DriveFile().apply {
            name = fileName
            parents = listOf(parentFolderId)
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
     * filename convention; GPS coordinates (for the Map link) are resolved
     * lazily by the adapter from the thumbnail bytes it already downloads.
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
                .setFields("nextPageToken, files(id, name, mimeType, createdTime)")
                .execute()
            files += result.files.orEmpty()
            pageToken = result.nextPageToken
        } while (pageToken != null)

        return files
            .sortedBy { it.createdTime?.value ?: 0L }
            .map { file ->
                val createdMs = file.createdTime?.value ?: 0L
                DrivePhoto(file.id, file.name, createdMs, cityLabelFromName(file.name))
            }
    }

    /** City label from the app's "Country_City_NNN.ext" upload convention, or a generic fallback. */
    private fun cityLabelFromName(fileName: String): String {
        val parts = fileName.substringBeforeLast('.').split("_")
        return if (parts.size >= 3) "${parts[1]}, ${parts[0]}" else "Other Photos"
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
    val createdTimeMs: Long,
    val cityLabel: String,
    val lat: Double? = null,
    val lon: Double? = null
)
