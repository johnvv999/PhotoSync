package com.johnvv.photosync

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.InputStream

/**
 * Thin wrapper around the Drive REST API v3, scoped to drive.file only
 * (this app can only see/manage files and folders it creates itself).
 */
class DriveServiceHelper(context: Context, accountName: String) {

    companion object {
        const val ROOT_FOLDER_NAME = "PhotoSync"
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }

    private val service: Drive

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
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
}
