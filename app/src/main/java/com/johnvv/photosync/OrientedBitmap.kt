package com.johnvv.photosync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Decodes JPEG bytes into a correctly-oriented bitmap. BitmapFactory ignores
 * the EXIF orientation tag, so portrait photos (which are stored as landscape
 * pixels plus a "rotate" tag) would otherwise render sideways.
 */
object OrientedBitmap {

    /**
     * Roughly what a photo needs to fill a list row on a phone screen. Well
     * short of the 12MP a decode at full size would give, and the difference
     * is the difference between 1MB and 48MB a row.
     */
    const val LIST_THUMBNAIL_PX = 1024

    fun decode(bytes: ByteArray): Bitmap? = decode(bytes, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    /**
     * Decodes at roughly [maxDimension] pixels on the long edge instead of full
     * resolution. A 12MP photo is ~48MB decoded, so anything showing a folder's
     * worth at once has to sample down or it will exhaust the heap partway down
     * the list.
     */
    fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null

        // inSampleSize halves each step, so take the largest power of two that
        // still leaves the image at or above the target size.
        var sample = 1
        while (longEdge / (sample * 2) >= maxDimension) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return decode(bytes, BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    }

    /** Applies the EXIF orientation tag from [bytes] to an already-decoded [bitmap]. */
    private fun decode(bytes: ByteArray, bitmap: Bitmap?): Bitmap? {
        bitmap ?: return null

        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap // normal / undefined — no transform needed
        }

        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }
}
