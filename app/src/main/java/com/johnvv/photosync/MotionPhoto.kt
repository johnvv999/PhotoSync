package com.johnvv.photosync

import android.graphics.BitmapFactory
import android.util.Log

private const val TAG = "MotionPhoto"

/**
 * Removes the video a "motion photo" carries around inside it.
 *
 * Samsung Motion Photo (and Google's equivalent) is not a series of shots — it
 * is one JPEG with a few seconds of MP4 appended after the image's end-of-image
 * marker. The still at the front is already the final frame, so dropping the
 * trailer loses nothing visible while typically cutting an 8–15 MB file back to
 * 3–4 MB. EXIF, including GPS, lives in the header and is untouched.
 *
 * Only the Drive copy is trimmed; the file on the phone keeps its video.
 */
object MotionPhoto {

    /**
     * Ignore trailers smaller than this. A few stray bytes after EOI are padding,
     * not a video, and rewriting the file to save them isn't worth the risk.
     */
    private const val MIN_TRAILER_BYTES = 64 * 1024

    /**
     * Returns [bytes] with any appended video removed, or null when there is
     * nothing to strip — or when the result couldn't be verified, in which case
     * the caller must upload the original untouched.
     *
     * Getting this wrong corrupts the backup rather than the original, so the
     * trimmed bytes are checked three ways before being handed back: they must
     * end on the end-of-image marker, still report the same dimensions as the
     * original, and actually decode.
     */
    fun stripTrailingVideo(bytes: ByteArray): ByteArray? {
        val end = endOfJpeg(bytes)
        if (end <= 0 || end >= bytes.size) return null
        if (bytes.size - end < MIN_TRAILER_BYTES) return null

        val trimmed = bytes.copyOf(end)

        if (trimmed.size < 2 ||
            (trimmed[trimmed.size - 2].toInt() and 0xFF) != 0xFF ||
            (trimmed[trimmed.size - 1].toInt() and 0xFF) != 0xD9
        ) {
            Log.w(TAG, "Trim didn't land on EOI — leaving the file alone")
            return null
        }

        val originalSize = decodedSize(bytes, sampleSize = 1, boundsOnly = true)
        val trimmedSize = decodedSize(trimmed, sampleSize = 1, boundsOnly = true)
        if (originalSize == null || originalSize != trimmedSize) {
            Log.w(TAG, "Trimmed image reports different dimensions — leaving the file alone")
            return null
        }

        // A bounds-only decode reads the header and would pass even on truncated
        // image data, so force a real decode as well. Subsampled: enough to prove
        // the scan data parses, without holding a full-resolution bitmap.
        if (decodedSize(trimmed, sampleSize = 8, boundsOnly = false) == null) {
            Log.w(TAG, "Trimmed image wouldn't decode — leaving the file alone")
            return null
        }

        Log.i(TAG, "Stripped ${bytes.size - end} bytes of motion-photo video")
        return trimmed
    }

    /** Decoded dimensions, or null if the bytes don't form a usable image. */
    private fun decodedSize(bytes: ByteArray, sampleSize: Int, boundsOnly: Boolean): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = boundsOnly
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (!boundsOnly) {
                if (bitmap == null) return null
                bitmap.recycle()
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth to options.outHeight
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Walks the JPEG's marker structure and returns the offset just past its
     * end-of-image marker, or -1 if the file isn't a JPEG we can follow.
     *
     * Scanning for the last 0xFFD9 in the file would be far simpler and quite
     * wrong: the appended MP4 is arbitrary binary that can contain those bytes
     * anywhere. Walking the structure is what makes the cut land in the right
     * place.
     */
    private fun endOfJpeg(bytes: ByteArray): Int {
        fun byteAt(index: Int) = bytes[index].toInt() and 0xFF

        if (bytes.size < 4 || byteAt(0) != 0xFF || byteAt(1) != 0xD8) return -1

        var i = 2
        while (i + 1 < bytes.size) {
            if (byteAt(i) != 0xFF) return -1 // lost sync with the segment structure

            // 0xFF may be repeated as fill before a marker.
            var markerAt = i + 1
            while (markerAt < bytes.size && byteAt(markerAt) == 0xFF) markerAt++
            if (markerAt >= bytes.size) return -1
            val marker = byteAt(markerAt)

            when {
                marker == 0xD9 -> return markerAt + 1 // EOI: exclusive end offset

                // Standalone markers: no length field follows.
                marker == 0x01 || marker in 0xD0..0xD7 -> i = markerAt + 1

                marker == 0xDA -> {
                    // Start of scan: entropy-coded data follows the header, in which
                    // 0xFF is stuffed as 0xFF00 and restart markers are legal. The
                    // next marker that is neither is where the scan ends.
                    if (markerAt + 3 >= bytes.size) return -1
                    val headerLength = (byteAt(markerAt + 1) shl 8) or byteAt(markerAt + 2)
                    if (headerLength < 2) return -1
                    var scan = markerAt + 1 + headerLength
                    while (scan + 1 < bytes.size) {
                        if (byteAt(scan) == 0xFF) {
                            val next = byteAt(scan + 1)
                            if (next != 0x00 && next != 0xFF && next !in 0xD0..0xD7) break
                        }
                        scan++
                    }
                    i = scan
                }

                else -> {
                    if (markerAt + 3 >= bytes.size) return -1
                    val segmentLength = (byteAt(markerAt + 1) shl 8) or byteAt(markerAt + 2)
                    if (segmentLength < 2) return -1
                    i = markerAt + 1 + segmentLength
                }
            }
        }
        return -1
    }
}
