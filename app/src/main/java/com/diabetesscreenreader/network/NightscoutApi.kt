package com.diabetesscreenreader.network

import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseTrend
import com.diabetesscreenreader.data.GlucoseUnit
import com.diabetesscreenreader.data.NightscoutEntry
import com.diabetesscreenreader.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class NightscoutApi(private val preferencesManager: PreferencesManager) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = preferencesManager.nightscoutUrl.first().trimEnd('/')
            if (url.isBlank()) {
                return@withContext Result.failure(Exception("Nightscout URL nicht konfiguriert"))
            }

            val request = Request.Builder()
                .url("$url/api/v1/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Server antwortet mit Status ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadReading(reading: GlucoseReading): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = preferencesManager.nightscoutUrl.first().trimEnd('/')
            val apiSecret = preferencesManager.nightscoutApiSecret.first()

            if (url.isBlank()) {
                return@withContext Result.failure(Exception("Nightscout URL nicht konfiguriert"))
            }

            val entry = reading.toNightscoutEntry()
            val jsonBody = json.encodeToString(listOf(entry))

            val requestBuilder = Request.Builder()
                .url("$url/api/v1/entries")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))

            if (apiSecret.isNotBlank()) {
                requestBuilder.addHeader("api-secret", hashApiSecret(apiSecret))
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                Result.success(responseBody)
            } else {
                Result.failure(Exception("Upload fehlgeschlagen: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadReadings(readings: List<GlucoseReading>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val url = preferencesManager.nightscoutUrl.first().trimEnd('/')
            val apiSecret = preferencesManager.nightscoutApiSecret.first()

            if (url.isBlank()) {
                return@withContext Result.failure(Exception("Nightscout URL nicht konfiguriert"))
            }

            if (readings.isEmpty()) {
                return@withContext Result.success(0)
            }

            val entries = readings.map { it.toNightscoutEntry() }
            val jsonBody = json.encodeToString(entries)

            val requestBuilder = Request.Builder()
                .url("$url/api/v1/entries")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))

            if (apiSecret.isNotBlank()) {
                requestBuilder.addHeader("api-secret", hashApiSecret(apiSecret))
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (response.isSuccessful) {
                Result.success(readings.size)
            } else {
                Result.failure(Exception("Upload fehlgeschlagen: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun hashApiSecret(secret: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(secret.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun GlucoseReading.toNightscoutEntry(): NightscoutEntry {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        return NightscoutEntry(
            sgv = getValueInUnit(GlucoseUnit.MG_DL).toInt(),
            direction = trend.toNightscoutDirection(),
            date = timestamp,
            dateString = dateFormat.format(Date(timestamp)),
            device = "DiabetesScreenReader/$source"
        )
    }

    private fun GlucoseTrend.toNightscoutDirection(): String = when (this) {
        GlucoseTrend.DOUBLE_UP -> "DoubleUp"
        GlucoseTrend.SINGLE_UP -> "SingleUp"
        GlucoseTrend.FORTY_FIVE_UP -> "FortyFiveUp"
        GlucoseTrend.FLAT -> "Flat"
        GlucoseTrend.FORTY_FIVE_DOWN -> "FortyFiveDown"
        GlucoseTrend.SINGLE_DOWN -> "SingleDown"
        GlucoseTrend.DOUBLE_DOWN -> "DoubleDown"
        GlucoseTrend.UNKNOWN -> "NOT COMPUTABLE"
    }
}
