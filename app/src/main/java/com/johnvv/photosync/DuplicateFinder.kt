package com.johnvv.photosync

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache

private const val TAG = "DuplicateFinder"

/**
 * Finds redundant photos in a Drive folder.
 *
 * Sending every pair of photos to Gemini would be hopelessly slow and
 * expensive, so this runs in two stages: a cheap on-device perceptual hash
 * clusters photos that *look* alike, then Gemini judges each cluster — which
 * is where the actual decision lives, since pixel similarity alone can't tell
 * a wasted second shot from two deliberately different moments.
 */
object DuplicateFinder {

    /** A cluster of similar photos, with the model's verdict if it gave one. */
    data class Group(
        val photos: List<DrivePhoto>,
        /** Index within [photos] of the one worth keeping; -1 when the AI didn't weigh in. */
        val keepIndex: Int,
        /** Indices within [photos] the AI flagged as safe to delete. */
        val redundantIndices: List<Int>,
        /** The model's one-line explanation, or a note that it couldn't be reached. */
        val reason: String
    )

    /** Below this Hamming distance (out of 64 hash bits) two photos are near-identical. */
    private const val HASH_DISTANCE_THRESHOLD = 10

    /** Gemini gets at most this many photos per group — the proxy enforces the same ceiling. */
    private const val MAX_GROUP_SIZE = 8

    // Hashes survive the activity being recreated, so re-running the scan after
    // deleting a few photos doesn't re-download the whole folder.
    private val hashCache = LruCache<String, Long>(2048)

    /**
     * Blocking — call from a background dispatcher. [onProgress] reports
     * (done, total) through the download-and-hash stage, which is the slow part.
     */
    fun findRedundantGroups(
        drive: DriveServiceHelper,
        photos: List<DrivePhoto>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {}
    ): List<Group> {
        val hashes = mutableMapOf<String, Long>()
        photos.forEachIndexed { index, photo ->
            hashCache.get(photo.fileId)?.let {
                hashes[photo.fileId] = it
                onProgress(index + 1, photos.size)
                return@forEachIndexed
            }
            try {
                val bytes = drive.downloadPhotoBytes(photo.fileId)
                // A hash only needs a thumbnail's worth of detail, and this loop
                // covers the whole folder — decoding at full size would blow the
                // heap well before it got through them.
                val bitmap = OrientedBitmap.decodeSampled(bytes, maxDimension = 256)
                if (bitmap != null) {
                    val hash = differenceHash(bitmap)
                    hashes[photo.fileId] = hash
                    hashCache.put(photo.fileId, hash)
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't hash ${photo.name}", e)
            }
            onProgress(index + 1, photos.size)
        }

        val clusters = cluster(photos.filter { it.fileId in hashes }, hashes)
        if (clusters.isEmpty()) return emptyList()

        onStage("ai")
        return clusters.mapNotNull { cluster -> judge(drive, cluster) }
    }

    /**
     * Union-find over all pairs: a photo similar to A and A similar to B puts
     * all three in one group, which is what a burst of near-identical shots
     * actually looks like. All-pairs is O(n²) XORs — negligible next to the
     * downloads that produced the hashes.
     */
    private fun cluster(photos: List<DrivePhoto>, hashes: Map<String, Long>): List<List<DrivePhoto>> {
        val parent = IntArray(photos.size) { it }

        fun find(a: Int): Int {
            var root = a
            while (parent[root] != root) root = parent[root]
            var walk = a
            while (parent[walk] != root) {
                val next = parent[walk]
                parent[walk] = root
                walk = next
            }
            return root
        }

        for (i in photos.indices) {
            for (j in i + 1 until photos.size) {
                val a = hashes[photos[i].fileId] ?: continue
                val b = hashes[photos[j].fileId] ?: continue
                if (java.lang.Long.bitCount(a xor b) <= HASH_DISTANCE_THRESHOLD) {
                    val rootA = find(i)
                    val rootB = find(j)
                    if (rootA != rootB) parent[rootA] = rootB
                }
            }
        }

        return photos.indices
            .groupBy { find(it) }
            .values
            .filter { it.size > 1 }
            // Keep each group in capture order so "the first one" means something.
            .map { indices -> indices.map { photos[it] }.sortedBy { it.chronoTimeMs } }
            .sortedBy { it.first().chronoTimeMs }
    }

    /** Asks Gemini which members of [cluster] are redundant, and maps its answer back onto the cluster. */
    private fun judge(drive: DriveServiceHelper, cluster: List<DrivePhoto>): Group? {
        val unjudged = Group(cluster, keepIndex = -1, redundantIndices = emptyList(), reason = "")

        // An oversized cluster (a long burst) still has to fit one request; the
        // extras stay visible in the group but unjudged, so nothing is hidden.
        // Photos that won't download drop out too — so track which cluster
        // positions actually went to the model. Gemini answers with indices into
        // what it was sent, and treating those as indices into the whole cluster
        // would tick the wrong photos for deletion the moment one is missing.
        val submitted = mutableListOf<Int>()
        val images = mutableListOf<ByteArray>()
        cluster.take(MAX_GROUP_SIZE).forEachIndexed { index, photo ->
            val bytes = try {
                drive.downloadPhotoBytes(photo.fileId)
            } catch (e: Exception) {
                null
            }
            if (bytes != null) {
                submitted += index
                images += bytes
            }
        }
        if (images.size < 2) return unjudged

        val verdict = GeminiClient.findRedundant(images) ?: return unjudged

        return Group(
            photos = cluster,
            keepIndex = submitted.getOrElse(verdict.keepIndex) { -1 },
            redundantIndices = verdict.redundantIndices.mapNotNull { submitted.getOrNull(it) },
            reason = verdict.reason
        )
    }

    /**
     * 64-bit difference hash: scale to 9x8 greyscale, then record whether each
     * pixel is brighter than its right-hand neighbour. Robust to resizing,
     * re-compression and small exposure shifts, which is exactly the difference
     * between two saves of the same shot.
     */
    private fun differenceHash(bitmap: Bitmap): Long {
        val width = 9
        val height = 8
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled != bitmap) scaled.recycle()

        fun luminance(pixel: Int): Int {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }

        var hash = 0L
        var bit = 0
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val left = luminance(pixels[y * width + x])
                val right = luminance(pixels[y * width + x + 1])
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }
}
