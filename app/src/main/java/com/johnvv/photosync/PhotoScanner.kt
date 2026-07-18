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

    /** Resolves each photo's GPS-derived city and groups them. Runs one geocode per photo — call off the main thread. */
    fun groupByCity(context: Context, photos: List<PhotoEntry>): List<CityGroup> {
        val resolver = context.contentResolver
        val groups = LinkedHashMap<String, Pair<String, MutableList<PhotoEntry>>>()
        for (photo in photos) {
            val location = resolver.openInputStream(photo.contentUri())?.use { stream ->
                LocationNaming.readLatLong(stream)
            }?.let { latLong ->
                LocationNaming.reverseGeocode(context, latLong[0], latLong[1])
            } ?: PhotoLocation(city = "NoGPS", country = "Unsorted")

            val display = "${location.city}, ${location.country}"
            groups.getOrPut(location.key()) { display to mutableListOf() }.second += photo
        }
        return groups.map { (key, pair) -> CityGroup(key, pair.first, pair.second) }
    }
}
