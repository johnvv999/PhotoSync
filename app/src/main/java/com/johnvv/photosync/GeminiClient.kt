package com.johnvv.photosync

import android.graphics.Bitmap
import android.util.Base64
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

        val url = URL(BuildConfig.GEMINI_PROXY_URL)
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-App-Secret", BuildConfig.GEMINI_PROXY_APP_SECRET)
            connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseJson = JSONObject(stream.bufferedReader().use { it.readText() })

            if (responseCode !in 200..299) {
                return responseJson.optString("error", "Request failed (code $responseCode)")
            }
            responseJson.optString("text", "No description returned.")
        } catch (e: Exception) {
            "Couldn't get info: ${e.message}"
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
