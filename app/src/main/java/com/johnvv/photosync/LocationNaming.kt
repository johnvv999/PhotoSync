package com.johnvv.photosync

import android.content.Context
import android.location.Geocoder
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.util.Locale

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

    /** Builds the final Drive filename, e.g. "France_Paris_001.jpg" */
    fun buildFileName(location: PhotoLocation, index: Int, extension: String = "jpg"): String {
        val padded = index.toString().padStart(3, '0')
        return "${location.key()}_$padded.$extension"
    }
}
