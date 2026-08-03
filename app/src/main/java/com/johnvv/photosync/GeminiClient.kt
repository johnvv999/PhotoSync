package com.johnvv.photosync

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a photo to the PhotoSync Gemini proxy (Cloudflare Worker, see
 * /cloudflare-worker) and returns a short description. The Worker holds the
 * real Gemini credential server-side — nothing Gemini-related is embedded in
 * the APK, since anything here would be trivially extractable.
 */
object GeminiClient {

    /** Blocking network call — run this from a background dispatcher. */
    fun describeImage(imageBytes: ByteArray, lat: Double? = null, lon: Double? = null): String {
        if (BuildConfig.GEMINI_PROXY_URL.isBlank()) {
            return "No Gemini proxy configured. Add GEMINI_PROXY_URL to local.properties and rebuild."
        }

        // Decode with EXIF orientation applied so Gemini sees the photo upright —
        // otherwise a portrait photo's sideways raw pixels make it describe the
        // image as "oriented sideways".
        val bitmap = OrientedBitmap.decode(imageBytes)
            ?: return "Couldn't read this photo."

        val base64Image = Base64.encodeToString(downscaleAndCompress(bitmap), Base64.NO_WRAP)

        val requestBody = JSONObject().apply {
            put("mimeType", "image/jpeg")
            put("data", base64Image)
            // Include GPS (if known) so Gemini can pin the actual location.
            if (lat != null && lon != null) {
                put("lat", lat)
                put("lon", lon)
            }
        }

        return try {
            val response = post(requestBody)
            response.optString("text", "No description returned.")
        } catch (e: ProxyException) {
            e.message ?: "Couldn't get info."
        } catch (e: Exception) {
            "Couldn't get info: ${e.message}"
        }
    }

    /** Gemini's verdict on one group of visually similar photos. */
    data class RedundancyVerdict(
        /** 0-based index within the submitted group of the photo worth keeping. */
        val keepIndex: Int,
        /** 0-based indices the model considers safe to delete. */
        val redundantIndices: List<Int>,
        val reason: String
    )

    /**
     * Asks Gemini which of [images] (a group the app already found visually
     * similar) are genuinely redundant. Returns null when the proxy can't
     * answer — the caller then falls back to showing the group unjudged, with
     * nothing pre-selected, rather than guessing on the model's behalf.
     *
     * Blocking network call — run this from a background dispatcher.
     */
    fun findRedundant(images: List<ByteArray>): RedundancyVerdict? {
        if (BuildConfig.GEMINI_PROXY_URL.isBlank() || images.size < 2) return null

        val encoded = JSONArray()
        for (bytes in images) {
            // Smaller than the describe path: judging "is this the same shot" needs
            // far less detail than naming a landmark, and a group sends several
            // images in one request — so sample down while decoding rather than
            // holding a handful of full-resolution bitmaps at once.
            val bitmap = OrientedBitmap.decodeSampled(bytes, maxDimension = 512) ?: return null
            val data = Base64.encodeToString(downscaleAndCompress(bitmap, maxDimension = 512), Base64.NO_WRAP)
            bitmap.recycle()
            encoded.put(JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", data)
            })
        }

        val requestBody = JSONObject().apply {
            put("mode", "compare")
            put("images", encoded)
        }

        return try {
            parseVerdict(post(requestBody).optString("text"), images.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pulls the verdict out of the model's reply. Models routinely wrap JSON in
     * a ```json fence despite being told not to, so find the outermost braces
     * rather than parsing the whole reply.
     */
    private fun parseVerdict(text: String, groupSize: Int): RedundancyVerdict? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        val json = try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: Exception) {
            return null
        }

        // The prompt asks for 1-based indices; convert and drop anything out of range.
        val keepIndex = (json.optInt("keep", 1) - 1).coerceIn(0, groupSize - 1)
        val redundant = json.optJSONArray("redundant")
        val indices = buildList {
            for (i in 0 until (redundant?.length() ?: 0)) {
                val index = redundant!!.optInt(i, 0) - 1
                if (index in 0 until groupSize && index != keepIndex) add(index)
            }
        }
        return RedundancyVerdict(keepIndex, indices, json.optString("reason").ifBlank { "" })
    }

    private class ProxyException(message: String) : Exception(message)

    /** POSTs [body] to the proxy and returns the parsed response, throwing on a non-2xx reply. */
    private fun post(body: JSONObject): JSONObject {
        val connection = (URL(BuildConfig.GEMINI_PROXY_URL).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-App-Secret", BuildConfig.GEMINI_PROXY_APP_SECRET)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseJson = JSONObject(stream.bufferedReader().use { it.readText() })
            if (responseCode !in 200..299) {
                throw ProxyException(responseJson.optString("error", "Request failed (code $responseCode)"))
            }
            return responseJson
        } finally {
            connection.disconnect()
        }
    }

    private fun downscaleAndCompress(bitmap: Bitmap, maxDimension: Int = 1024): ByteArray {
        val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
        return output.toByteArray()
    }
}
