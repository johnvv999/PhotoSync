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

    /** Finds the PhotoSync root folder, creating it if it doesn't exist yet. Returns its file ID. */
    fun getOrCreateRootFolder(): String {
        val query = "mimeType='$MIME_FOLDER' and name='$ROOT_FOLDER_NAME' " +
            "and trashed=false and 'root' in parents"
        val existing = service.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        existing.files?.firstOrNull()?.let { return it.id }

        val folderMetadata = DriveFile().apply {
            name = ROOT_FOLDER_NAME
            mimeType = MIME_FOLDER
        }
        val created = service.files().create(folderMetadata)
            .setFields("id")
            .execute()
        return created.id
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

    /** Returns the shareable webViewLink for a file/folder (folder must already be shared "anyone with the link"). */
    fun getWebViewLink(fileId: String): String? {
        return service.files().get(fileId).setFields("webViewLink").execute().webViewLink
    }

    /** Lists the image files directly inside [folderId], oldest first. */
    fun listPhotosInFolder(folderId: String): List<DrivePhoto> {
        val result = service.files().list()
            .setSpaces("drive")
            .setFields("files(id, name, mimeType, parents, trashed, createdTime)")
            .execute()
        return result.files.orEmpty()
            .filter { file ->
                file.trashed != true &&
                    file.mimeType?.startsWith("image/") == true &&
                    folderId in file.parents.orEmpty()
            }
            .sortedBy { it.createdTime?.value ?: 0L }
            .map { file ->
                val createdMs = file.createdTime?.value ?: 0L
                val (cityLabel, latLong) = resolveCityLabelAndGps(file.id, file.name)
                DrivePhoto(file.id, file.name, createdMs, cityLabel, latLong?.get(0), latLong?.get(1))
            }
    }

    /**
     * City label plus raw GPS coordinates (if any) for a Drive photo. The city
     * label is parsed from this app's own upload naming convention
     * ("Country_City_NNN.ext") when possible; otherwise it's resolved from the
     * photo's GPS EXIF, the same way [PhotoScanner.groupByCity] does for
     * on-device photos. Either way, GPS coordinates are read from EXIF whenever
     * present — even app-uploaded photos already have a resolved city name in
     * their filename, but the Map link still needs the raw coordinates.
     */
    private fun resolveCityLabelAndGps(fileId: String, fileName: String): Pair<String, DoubleArray?> {
        val parts = fileName.substringBeforeLast('.').split("_")
        val nameLabel = if (parts.size >= 3) "${parts[1]}, ${parts[0]}" else null

        val latLong = try {
            val bytes = downloadPhotoPrefix(fileId)
            ByteArrayInputStream(bytes).use { LocationNaming.readLatLong(it) }
        } catch (e: Exception) {
            null
        }

        if (nameLabel != null) return nameLabel to latLong

        val location = latLong?.let { LocationNaming.reverseGeocode(appContext, it[0], it[1]) }
        val cityLabel = location?.let { "${it.city}, ${it.country}" } ?: "No GPS Data"
        return cityLabel to latLong
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

/** A single image file listed from a Drive folder. */
data class DrivePhoto(
    val fileId: String,
    val name: String,
    val createdTimeMs: Long,
    val cityLabel: String,
    val lat: Double? = null,
    val lon: Double? = null
)
