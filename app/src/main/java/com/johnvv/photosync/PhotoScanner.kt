package com.johnvv.photosync

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** A single device photo considered for manual sync (date-range/city/individual pickers). */
data class PhotoEntry(val id: Long, val dateTakenMs: Long) {
    fun contentUri(): Uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
}

/** A city grouping produced by [PhotoScanner.groupByCity], ready for a city picker list. */
data class CityGroup(val locationKey: String, val displayName: String, val photos: List<PhotoEntry>)

/**
 * Queries on-device camera photos and (optionally) resolves their GPS-derived
 * city, for the manual sync control screen's date-range/city/individual pickers.
 */
object PhotoScanner {

    /** Photos with DATE_TAKEN in [[startEpochMs], [endEpochMs]] (either bound may be null for open-ended). */
    fun queryPhotos(context: Context, startEpochMs: Long?, endEpochMs: Long?): List<PhotoEntry> {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (startEpochMs != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} >= ?"
            args += startEpochMs.toString()
        }
        if (endEpochMs != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} <= ?"
            args += endEpochMs.toString()
        }
        val selection = clauses.joinToString(" AND ").ifEmpty { null }
        // Newest first, because this feeds the photo-picker grid where recent
        // photos should be at the top. Anything that assigns sequence numbers
        // must re-sort ascending first — uploading in this order is what numbered
        // a library backwards, giving the newest photo index 001.
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        val entries = mutableListOf<PhotoEntry>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, if (args.isEmpty()) null else args.toTypedArray(), sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                entries += PhotoEntry(cursor.getLong(idCol), cursor.getLong(dateCol))
            }
        }
        return entries
    }

    /**
     * Resolves each photo's GPS-derived city and groups them, ordered
     * alphabetically by country and then by city. Runs one geocode per photo —
     * call off the main thread.
     *
     * The groups are built in the order photos are encountered (newest first),
     * which puts the picker's checkboxes in an order that reads as arbitrary;
     * sorting them means every city of a country sits together.
     */
    fun groupByCity(
        context: Context,
        photos: List<PhotoEntry>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<CityGroup> {
        val resolver = context.contentResolver
        val groups = LinkedHashMap<String, Pair<PhotoLocation, MutableList<PhotoEntry>>>()
        photos.forEachIndexed { index, photo ->
            onProgress(index + 1, photos.size)
            // Unredacted, or the GPS tag won't be there to group by — see OriginalMedia.
            val location = OriginalMedia.open(resolver, photo.contentUri())?.use { stream ->
                LocationNaming.readLatLong(stream)
            }?.let { latLong ->
                LocationNaming.reverseGeocode(context, latLong[0], latLong[1])
            } ?: PhotoLocation(city = "NoGPS", country = "Unsorted")

            groups.getOrPut(location.key()) { location to mutableListOf() }.second += photo
        }
        // The map key is only ever location.key(), so it can be recomputed and the
        // sort works off the values alone.
        return groups.values
            // lowercase() (Locale.ROOT) rather than a case-insensitive comparator:
            // it keeps the ordering deterministic regardless of device locale.
            .sortedWith(compareBy({ it.first.country.lowercase() }, { it.first.city.lowercase() }))
            .map { (location, entries) ->
                CityGroup(location.key(), LocationNaming.displayLabel(location), entries)
            }
    }
}
