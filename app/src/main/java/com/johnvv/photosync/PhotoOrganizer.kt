package com.johnvv.photosync

import android.content.Context
import android.util.Log

private const val TAG = "PhotoOrganizer"

/**
 * Repairs the location information already baked into a Drive folder's photo
 * filenames.
 *
 * Photos taken indoors, in a tunnel, or before the phone got a fix land on
 * Drive as "Unsorted_NoGPS_NNN.jpg" and show up as "NoGPS, Unsorted" when
 * browsing. In practice those photos were taken moments after — and in the
 * same place as — a photo that *did* have GPS, so the fix is to walk the
 * folder in capture order and let each location-less photo inherit the last
 * real location seen. The whole folder is then renumbered so each location's
 * NNN sequence runs in capture order rather than upload order.
 */
object PhotoOrganizer {

    /** Outcome of a [fixLocationsAndOrder] run. */
    data class Result(
        /** Every photo in the folder, chronological, with names/labels as they now stand on Drive. */
        val photos: List<DrivePhoto>,
        val renamed: Int,
        val locationsInherited: Int,
        /** Photos Drive refused to modify — ones the app didn't upload, so drive.file can't touch them. */
        val skipped: Int,
        /** Photos still without a real location: nothing earlier in the folder had one to copy. */
        val stillUnlocated: Int
    )

    /**
     * Blocking — call from a background dispatcher.
     *
     * [onProgress] is invoked as (done, total) so the caller can show progress;
     * the pass makes one Drive request per photo that needs changing.
     */
    fun fixLocationsAndOrder(
        context: Context,
        drive: DriveServiceHelper,
        folderId: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result {
        // listPhotosInFolder already returns capture-time order.
        val photos = drive.listPhotosInFolder(folderId)
        if (photos.isEmpty()) return Result(emptyList(), 0, 0, 0, 0)

        val geocodeCache = mutableMapOf<String, PhotoLocation>()
        val resolved = mutableListOf<Pair<DrivePhoto, PhotoLocation>>()
        var lastRealLocation: PhotoLocation? = null
        var inherited = 0
        var stillUnlocated = 0

        for (photo in photos) {
            val parsed = LocationNaming.parseFileName(photo.name)
            var location = parsed?.location

            // A photo whose name is already a real place keeps it, and becomes the
            // location that any following GPS-less photos inherit.
            if (location != null && !LocationNaming.isPlaceholder(location)) {
                lastRealLocation = location
                resolved += photo to location
                continue
            }

            // Drive parsed the EXIF for us during the listing, so a photo that does
            // have a fix — but was named "NoGPS" because reverse geocoding failed
            // back when it was uploaded — can be resolved properly now.
            if (photo.lat != null && photo.lon != null) {
                val geocoded = geocodeCached(context, geocodeCache, photo.lat, photo.lon)
                if (!LocationNaming.isPlaceholder(geocoded)) {
                    lastRealLocation = geocoded
                    resolved += photo to geocoded
                    continue
                }
            }

            // No location of its own: inherit the last one we saw. Photos before the
            // very first located photo have nothing to inherit and stay as they are.
            val inheritedLocation = lastRealLocation
            if (inheritedLocation != null) {
                inherited++
                resolved += photo to inheritedLocation
            } else {
                stillUnlocated++
                resolved += photo to (location ?: PhotoLocation(city = "NoGPS", country = "Unsorted"))
            }
        }

        // Renumber every location's sequence in capture order, so France_Paris_001
        // really is the first Paris photo taken and not the first one uploaded.
        val indexStore = LocationIndexStore(context)
        val counters = mutableMapOf<String, Int>()
        val updated = mutableListOf<DrivePhoto>()
        var renamed = 0
        var skipped = 0

        resolved.forEachIndexed { position, (photo, location) ->
            val key = location.key()
            val index = (counters[key] ?: 0) + 1
            counters[key] = index

            val extension = photo.name.substringAfterLast('.', "jpg")
            val newName = LocationNaming.buildFileName(location, index, extension)
            val nameChanged = newName != photo.name

            try {
                drive.updatePhotoNameAndOrder(
                    fileId = photo.fileId,
                    newName = if (nameChanged) newName else null,
                    orderIndex = position + 1,
                    // chronoTimeMs, not takenTimeMs: a Drive update with no
                    // modifiedTime of its own resets it to now, so a photo whose
                    // EXIF time Drive couldn't read would have its date bumped to
                    // today by the very pass meant to tidy it up.
                    takenTimeMs = photo.chronoTimeMs
                )
                if (nameChanged) renamed++
                updated += photo.copy(
                    name = newName,
                    cityLabel = LocationNaming.displayLabel(location),
                    orderIndex = position + 1
                )
            } catch (e: Exception) {
                // Almost always a 403 on a photo this app didn't upload: drive.file
                // grants write access only to the app's own files. Keep it in the
                // list under its existing name rather than dropping it from view.
                Log.w(TAG, "Couldn't update ${photo.name}", e)
                skipped++
                updated += photo
            }

            onProgress(position + 1, resolved.size)
        }

        // Keep the local per-city counters ahead of what we just wrote, so the next
        // upload doesn't hand out an index this pass already used.
        counters.forEach { (key, highest) -> indexStore.raiseTo(key, highest) }

        return Result(updated, renamed, inherited, skipped, stillUnlocated)
    }

    /**
     * Renames a single photo to sit under [newLocation], used when the user
     * edits a location by hand. Returns the photo with its new name, or null
     * if Drive refused the change.
     */
    fun relocate(
        context: Context,
        drive: DriveServiceHelper,
        photo: DrivePhoto,
        newLocation: PhotoLocation
    ): DrivePhoto? {
        val indexStore = LocationIndexStore(context)
        val index = indexStore.nextIndex(newLocation.key())
        val extension = photo.name.substringAfterLast('.', "jpg")
        val newName = LocationNaming.buildFileName(newLocation, index, extension)
        return try {
            drive.updatePhotoNameAndOrder(photo.fileId, newName, photo.orderIndex, photo.chronoTimeMs)
            photo.copy(name = newName, cityLabel = LocationNaming.displayLabel(newLocation))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't rename ${photo.name}", e)
            null
        }
    }

    /** Reverse-geocodes with a cache, since consecutive photos share a location. */
    private fun geocodeCached(
        context: Context,
        cache: MutableMap<String, PhotoLocation>,
        lat: Double,
        lon: Double
    ): PhotoLocation {
        // ~100m buckets: fine enough that neighbouring cities stay distinct, coarse
        // enough that a walk around one city is a single Geocoder call.
        val key = "%.3f,%.3f".format(lat, lon)
        return cache.getOrPut(key) { LocationNaming.reverseGeocode(context, lat, lon) }
    }
}
