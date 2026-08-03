package com.johnvv.photosync

import android.content.Context
import android.location.Geocoder
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.util.Locale

private const val TAG = "LocationNaming"

data class PhotoLocation(val city: String, val country: String) {
    /** Safe-for-filename key, e.g. "France_Paris" */
    fun key(): String = "${sanitize(country)}_${sanitize(city)}"

    private fun sanitize(s: String): String =
        s.trim().replace(Regex("[^A-Za-z0-9]+"), "")
            .ifEmpty { "Unknown" }
}

object LocationNaming {

    /** Reads GPS EXIF from the given stream. Returns null if no usable GPS tag is present. */
    fun readLatLong(inputStream: InputStream): DoubleArray? {
        val exif = ExifInterface(inputStream)
        val latLong = FloatArray(2)
        if (!exif.getLatLong(latLong)) return null
        // (0, 0) — "Null Island", open ocean off West Africa — is the standard
        // signature of a camera app writing a blank/unset GPS tag rather than a
        // real location, not an actual photo location.
        if (latLong[0] == 0f && latLong[1] == 0f) return null
        return doubleArrayOf(latLong[0].toDouble(), latLong[1].toDouble())
    }

    /** What one EXIF header read yields: where the photo was taken, and when. */
    class ExifFacts(val coords: DoubleArray?, val takenMs: Long?)

    /**
     * Reads GPS and capture time in a single pass. A stream can only be consumed
     * once, so callers that need both must not go through [readLatLong] twice —
     * that would mean reopening the photo just to re-parse the same header.
     */
    fun readExifFacts(inputStream: InputStream): ExifFacts = try {
        val exif = ExifInterface(inputStream)
        val latLong = FloatArray(2)
        @Suppress("DEPRECATION")
        val coords = if (exif.getLatLong(latLong) && !(latLong[0] == 0f && latLong[1] == 0f)) {
            doubleArrayOf(latLong[0].toDouble(), latLong[1].toDouble())
        } else {
            null
        }
        ExifFacts(coords, exif.dateTimeOriginal?.takeIf { it > 0 })
    } catch (e: Exception) {
        ExifFacts(null, null)
    }

    /**
     * Writes [lat]/[lon] into [file]'s EXIF in place.
     *
     * Used for photos that had no fix of their own and took a neighbour's
     * location: stamping the coordinates in means the Drive copy carries a
     * location too, so the Map link and the Edit screen work for it rather than
     * it being a photo whose place is known only from its filename. The
     * coordinates are inferred from an adjacent photo, not measured — which is
     * exactly the approximation the inheritance rule already accepts.
     *
     * Best effort: ExifInterface can only write JPEG/PNG/WebP, so anything else
     * is left alone and simply uploads without a fix.
     */
    fun writeLatLong(file: File, lat: Double, lon: Double): Boolean = try {
        ExifInterface(file.absolutePath).apply {
            setLatLong(lat, lon)
            saveAttributes()
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't stamp GPS into ${file.name}", e)
        false
    }

    /** Reverse-geocodes to city/country. Falls back to "Unsorted"/"NoGPS" on failure. */
    fun reverseGeocode(context: Context, lat: Double, lon: Double): PhotoLocation {
        return try {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val addr = results?.firstOrNull()
            val city = addr?.locality ?: addr?.subAdminArea ?: "Unsorted"
            val country = addr?.countryName ?: "Unknown"
            PhotoLocation(city, country)
        } catch (e: Exception) {
            PhotoLocation("Unsorted", "NoGPS")
        }
    }

    /**
     * Builds the final Drive filename, e.g. "France_Paris_001.jpg".
     *
     * Chronology lives in the counter alone: photos are uploaded and renumbered
     * in capture order, so within a location the index *is* the order.
     */
    fun buildFileName(location: PhotoLocation, index: Int, extension: String = "jpg"): String {
        val padded = index.toString().padStart(3, '0')
        return "${location.key()}_$padded.$extension"
    }

    /**
     * Placeholder tokens a filename carries when the photo had no usable
     * location at upload time. The upload path writes "Unsorted_NoGPS" when
     * EXIF has no GPS at all, while [reverseGeocode] independently falls back
     * to "Unsorted"/"NoGPS"/"Unknown" when the fix existed but the lookup
     * failed — so any of these, in either the city or country slot, means
     * "this photo still needs a real location".
     */
    private val PLACEHOLDER_TOKENS = setOf("NoGPS", "Unsorted", "Unknown")

    /** True when [location] is one of the "we couldn't work out where this was" fallbacks. */
    fun isPlaceholder(location: PhotoLocation): Boolean =
        location.city in PLACEHOLDER_TOKENS || location.country in PLACEHOLDER_TOKENS

    /** A Drive filename decomposed back into the "Country_City_NNN.ext" scheme. */
    data class ParsedName(val location: PhotoLocation, val index: Int, val extension: String)

    /**
     * Reads a filename written by [buildFileName] back apart. Null if it isn't in
     * the scheme.
     *
     * Reads the current "Country_City_NNN" form and also the briefly-used
     * "Country_City_yyyyMMdd_HHmmss_NNN" one. Names are no longer written with a
     * timestamp, but any folder that picked one up still has to parse — otherwise
     * every photo in it would read as having no location, and the Edit screen
     * would treat a correctly-named library as unsorted instead of renaming it
     * back.
     */
    fun parseFileName(fileName: String): ParsedName? {
        val extension = fileName.substringAfterLast('.', "jpg")
        val parts = fileName.substringBeforeLast('.').split("_")

        // The counter is always zero-padded to at least three digits, which is what
        // separates a real "France_Paris_007" from an unrelated file that merely
        // happens to have two underscores ("Beach_Trip_2"). Mistaking one for the
        // other would let a junk filename masquerade as a genuine location and get
        // inherited by every GPS-less photo that follows it.
        fun counterAt(position: Int): Int? = parts.getOrNull(position)
            ?.takeIf { it.length >= 3 && it.all(Char::isDigit) }
            ?.toIntOrNull()

        val index = when {
            // Country_City_yyyyMMdd_HHmmss_NNN
            parts.size == 5 &&
                parts[2].length == 8 && parts[2].all(Char::isDigit) &&
                parts[3].length == 6 && parts[3].all(Char::isDigit) -> counterAt(4)
            // Country_City_NNN (pre-timestamp)
            parts.size == 3 -> counterAt(2)
            else -> null
        } ?: return null

        return ParsedName(PhotoLocation(city = parts[1], country = parts[0]), index, extension)
    }

    /**
     * True when [fileName] carries no real place — either it isn't in the naming
     * scheme at all, or it holds the placeholder tokens. These are exactly the
     * photos that display under "Other Photos", and the two must stay in
     * agreement.
     */
    fun isUnlocated(fileName: String): Boolean {
        val parsed = parseFileName(fileName) ?: return true
        return isPlaceholder(parsed.location)
    }

    /** How a location is shown to the user, matching the browse screen's "City, Country" headers. */
    fun displayLabel(location: PhotoLocation): String = "${location.city}, ${location.country}"

    /**
     * "Country, City" for the Edit screen, or null when [fileName] carries no
     * real place — those show as "Other Photos".
     *
     * Country leads here because the Edit screen is for putting a library in
     * order, where every city of a country belongs together; the browse screen
     * keeps city first, since there you already know where you were.
     */
    fun countryFirstLabel(fileName: String): String? =
        parseFileName(fileName)?.location
            ?.takeUnless { isPlaceholder(it) }
            ?.let { "${it.country}, ${it.city}" }

    /**
     * Reads back what the Edit screen's location field displays — "Country,
     * City". A single word is taken as the city, keeping [fallbackCountry],
     * since correcting a misgeocoded city is the common edit.
     */
    fun fromCountryFirstLabel(text: String, fallbackCountry: String): PhotoLocation? {
        val parts = text.trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> null
            parts.size == 1 -> PhotoLocation(city = parts[0], country = fallbackCountry)
            else -> PhotoLocation(city = parts[1], country = parts[0])
        }
    }

}
