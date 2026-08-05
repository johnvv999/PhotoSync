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

    /** What a pass over the folder achieved. */
    data class BulkResult(
        /** Photos described by the AI just now. */
        val created: Int,
        /** Photos that already had a description in the shared cache — no AI call needed. */
        val reused: Int,
        /** Photos that couldn't be described, usually a download or network failure. */
        val failed: Int,
        /** How many were missing a description when the pass started. */
        val considered: Int
    )

    /**
     * Gives every photo without a description one, and writes it to the file.
     *
     * Doing this ahead of time is the only way Info is ever instant on a photo
     * nobody has opened: the alternative is one person waiting ten seconds for
     * a download and an AI call, on each photo, whenever they happen to tap it.
     *
     * Sequential on purpose. Each photo means pulling a few megabytes into
     * memory, and the point of this pass is that nobody is waiting on it — so
     * there is nothing to gain from running several at once and a heap to lose.
     *
     * Blocking — call from a background dispatcher.
     */
    fun describeMissing(
        drive: DriveServiceHelper,
        photos: List<DrivePhoto>,
        onCopyProgress: (Int, Int) -> Unit = { _, _ -> },
        onDescribeProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BulkResult {
        val missing = photos.filter { it.description.isNullOrBlank() }
        var created = 0
        var reused = 0
        var failed = 0

        // Pass one: photos already described somewhere else. Browsing the
        // website describes photos too, and those answers can only live in the
        // proxy's cache — the page has no way to write to Drive. Copying them
        // across costs one small request each and no AI call at all, so they
        // are worth clearing out before anything expensive starts.
        val stillMissing = mutableListOf<DrivePhoto>()
        missing.forEachIndexed { index, photo ->
            onCopyProgress(index + 1, missing.size)
            val remembered = GeminiClient.lookupDescription(photo.fileId, photo.md5Checksum)
            if (remembered == null) {
                stillMissing += photo
                return@forEachIndexed
            }
            DrivePhotoCache.putDescription(photo.fileId, remembered)
            storeOnDrive(drive, photo, remembered)
            reused++
        }

        // Pass two: the ones nobody has ever described — a download and an AI
        // call each, which is the part that takes real time.
        stillMissing.forEachIndexed { index, photo ->
            onDescribeProgress(index + 1, stillMissing.size)

            val bytes = DrivePhotoCache.bytes(drive, photo.fileId)
            if (bytes == null) {
                failed++
                return@forEachIndexed
            }
            val gps = coords(drive, photo)
            val description = GeminiClient.describeImage(
                bytes, gps?.get(0), gps?.get(1), photo.fileId, photo.md5Checksum
            )
            if (looksLikeFailure(description)) {
                // Counted as a failure rather than stored: writing a network
                // error onto the file would make one bad moment permanent, and
                // the photo would never be tried again.
                failed++
                return@forEachIndexed
            }
            DrivePhotoCache.putDescription(photo.fileId, description)
            storeOnDrive(drive, photo, description)
            created++
        }

        return BulkResult(created, reused, failed, missing.size)
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
