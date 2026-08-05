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

    /**
     * Asks the proxy whether this photo has already been described, without
     * sending the photo. Null when it hasn't, or when the proxy can't be
     * reached — either way the caller falls back to describing it properly.
     *
     * Worth a round trip of its own: the alternative is downloading the photo
     * and posting it back, and descriptions written by the public browsing page
     * live only in the proxy's cache — the page has no credential to write them
     * onto the Drive file, so without asking, the app pays Gemini again for a
     * photo somebody has already described.
     *
     * Blocking network call — run this from a background dispatcher.
     */
    fun lookupDescription(photoId: String, version: String?): String? {
        if (BuildConfig.GEMINI_PROXY_URL.isBlank()) return null
        val body = JSONObject().apply {
            put("mode", "lookup")
            put("photoId", photoId)
            if (version != null) put("version", version)
        }
        return try {
            post(body).optString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Blocking network call — run this from a background dispatcher. */
    fun describeImage(
        imageBytes: ByteArray,
        lat: Double? = null,
        lon: Double? = null,
        photoId: String? = null,
        version: String? = null
    ): String {
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
            // Named so the proxy keeps the answer for whoever asks next —
            // this phone, another device, or the browsing page.
            if (photoId != null) {
                put("photoId", photoId)
                if (version != null) put("version", version)
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
