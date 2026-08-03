package com.johnvv.photosync

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.InputStream

private const val TAG = "OriginalMedia"

/**
 * Opens a MediaStore photo with its GPS EXIF intact.
 *
 * From Android 10 onwards `openInputStream` hands back a *redacted* copy of the
 * image with the location tags stripped out — and it does that even when
 * ACCESS_MEDIA_LOCATION has been granted. Holding the permission only earns the
 * right to ask for the unredacted file; [MediaStore.setRequireOriginal] is what
 * actually asks. Without it every photo reads as having no GPS, which is how a
 * whole library ends up named "Unsorted_NoGPS_NNN.jpg" — and, because the same
 * stream is what gets uploaded, how the copies on Drive lose their coordinates
 * permanently.
 */
object OriginalMedia {

    /**
     * Opens [uri] unredacted where the platform supports it, falling back to the
     * ordinary stream if the original isn't available — a redacted photo is
     * still worth uploading, it just can't be located.
     */
    fun open(resolver: ContentResolver, uri: Uri): InputStream? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return resolver.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (e: UnsupportedOperationException) {
                // Some providers/ROMs can't serve the original (e.g. a cloud-only
                // photo that isn't on the device).
                Log.w(TAG, "No original available for $uri", e)
            } catch (e: SecurityException) {
                // ACCESS_MEDIA_LOCATION not granted, or revoked since startup.
                Log.w(TAG, "Not permitted to read original media for $uri", e)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open original for $uri", e)
            }
        }
        return resolver.openInputStream(uri)
    }
}
