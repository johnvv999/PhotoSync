package com.johnvv.photosync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Sends a photo to the Gemini API (free-tier flash model) and returns a short description. */
object GeminiClient {

    private const val MODEL = "gemini-flash-latest"
    private const val PROMPT =
        "Briefly describe what's in this photo and identify any recognizable landmark, location, or point of interest, in 2-3 sentences."

    /** Blocking network call — run this from a background dispatcher. */
    fun describeImage(imageBytes: ByteArray): String {
        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            return "No Gemini API key configured. Add GEMINI_API_KEY to local.properties and rebuild."
        }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return "Couldn't read this photo."

        val base64Image = Base64.encodeToString(downscaleAndCompress(bitmap), Base64.NO_WRAP)

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put(
                    "parts", JSONArray()
                        .put(JSONObject().put("text", PROMPT))
                        .put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        }))
                )
            }))
        }

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
        )
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }

            if (responseCode !in 200..299) {
                return "Gemini request failed (code $responseCode): $responseText"
            }

            JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
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
