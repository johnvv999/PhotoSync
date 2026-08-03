package com.johnvv.photosync

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

    /** Gemini's description of [photo], cached. Null when the photo couldn't be downloaded. */
    fun describe(drive: DriveServiceHelper, photo: DrivePhoto): String? {
        DrivePhotoCache.description(photo.fileId)?.let { return it }

        val bytes = DrivePhotoCache.bytes(drive, photo.fileId) ?: return null
        val gps = coords(drive, photo)
        val description = GeminiClient.describeImage(bytes, gps?.get(0), gps?.get(1))
        DrivePhotoCache.putDescription(photo.fileId, description)
        return description
    }
}
