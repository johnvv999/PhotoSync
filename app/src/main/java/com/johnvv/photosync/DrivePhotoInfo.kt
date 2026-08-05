package com.johnvv.photosync

import android.util.Log

private const val TAG = "DrivePhotoInfo"

/**
 * Resolves the two things both photo screens ask about a Drive photo — its GPS
 * fix and its Gemini description — through [DrivePhotoCache], so the browse and
 * edit screens share one answer per photo instead of each paying for its own.
 *
 * Blocking — call from a background dispatcher.
 */
object DrivePhotoInfo {

    /** GPS for [photo], preferring what the folder listing already told us. */
    fun coords(drive: DriveServiceHelper, photo: DrivePhoto): DoubleArray? {
        if (DrivePhotoCache.hasGps(photo.fileId)) return DrivePhotoCache.gps(photo.fileId)

        // Drive parses EXIF during the listing, so most photos arrive with their
        // coordinates already known; only fall back to reading the file's own
        // header for ones Drive didn't report a fix for.
        val coords = if (photo.lat != null && photo.lon != null) {
            doubleArrayOf(photo.lat, photo.lon)
        } else {
            drive.readGpsCoords(photo.fileId)
        }
        DrivePhotoCache.putGps(photo.fileId, coords)
        return coords
    }

    /**
     * A description of [photo]: the one stored on the Drive file if it has one,
     * otherwise a fresh one from Gemini, which is then saved to the file.
     *
     * Storing it on Drive means each photo is described once rather than once
     * per device and per viewer — the browsing page reads the same field, so
     * anyone opening the shared link gets the text immediately instead of
     * spending a Gemini call to be told what someone else already knows.
     *
     * Null when the photo couldn't be downloaded.
     */
    fun describe(drive: DriveServiceHelper, photo: DrivePhoto): String? {
        DrivePhotoCache.description(photo.fileId)?.let { return it }

        photo.description?.takeIf { it.isNotBlank() }?.let { stored ->
            DrivePhotoCache.putDescription(photo.fileId, stored)
            return stored
        }

        // Not on the file, but somebody may still have described it — the
        // browsing page can only leave its answers in the proxy's cache, having
        // no credential to write to Drive. Asking costs one small request; not
        // asking costs a photo download and a Gemini call to be told what is
        // already known.
        GeminiClient.lookupDescription(photo.fileId, photo.md5Checksum)?.let { remembered ->
            DrivePhotoCache.putDescription(photo.fileId, remembered)
            // Copied onto the file, so from now on it arrives with the listing
            // and nobody needs to ask at all.
            storeOnDrive(drive, photo, remembered)
            return remembered
        }

        val bytes = DrivePhotoCache.bytes(drive, photo.fileId) ?: return null
        val gps = coords(drive, photo)
        val description = GeminiClient.describeImage(
            bytes, gps?.get(0), gps?.get(1), photo.fileId, photo.md5Checksum
        )
        DrivePhotoCache.putDescription(photo.fileId, description)

        storeOnDrive(drive, photo, description)
        return description
    }

    /**
     * Best effort, and only for a real answer: a network hiccup returns ordinary
     * text, and writing that to the file would make one bad moment permanent for
     * every future viewer. A 403 here just means the photo was not uploaded by
     * this app, which drive.file cannot modify.
     */
    private fun storeOnDrive(drive: DriveServiceHelper, photo: DrivePhoto, description: String) {
        if (looksLikeFailure(description)) return
        try {
            drive.setDescription(photo.fileId, description)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't store description for ${photo.name}", e)
        }
    }

    private fun looksLikeFailure(text: String): Boolean =
        text.isBlank() ||
            text.startsWith("Couldn't", ignoreCase = true) ||
            text.startsWith("No Gemini proxy", ignoreCase = true) ||
            text.startsWith("Request failed", ignoreCase = true) ||
            text.startsWith("No description returned", ignoreCase = true)
}
