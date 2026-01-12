package com.diabetesscreenreader.network

import com.diabetesscreenreader.data.*
import android.util.Log
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

    companion object {
        private const val TAG = "NightscoutApi"
    }

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

    // Store last known values for change detection
    private var lastBatteryPercent: Int? = null
    private var lastReservoir: Double? = null

    // ════════════════════════════════════════════════════════════════════════════
    // Core HTTP Helper - All requests go through here
    // ════════════════════════════════════════════════════════════════════════════

    private data class ApiConfig(val baseUrl: String, val apiSecret: String)

    private suspend fun getConfig(): Result<ApiConfig> {
        val rawUrl = preferencesManager.nightscoutUrl.first()
        val baseUrl = normalizeUrl(rawUrl)
        val apiSecret = preferencesManager.nightscoutApiSecret.first().trim()

        if (baseUrl.isBlank()) {
            return Result.failure(Exception("Nightscout URL nicht konfiguriert"))
        }

        return Result.success(ApiConfig(baseUrl, apiSecret))
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trimEnd('/')
        if (normalized.lowercase().endsWith("/api/v1")) {
            normalized = normalized.dropLast(7)
        }
        return normalized
    }

    private fun prepareApiSecret(secret: String): String {
        if (secret.isBlank()) return ""

        // Nightscout tokens (admin-xxx, readable-xxx, etc.) - don't hash
        if (secret.contains("-") && secret.split("-").size == 2) {
            val prefix = secret.split("-")[0].lowercase()
            if (prefix in listOf("admin", "readable", "reader", "denied", "device", "food")) {
                return secret
            }
        }

        // Already a 40-char SHA-1 hash - don't hash again
        if (secret.length == 40 && secret.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return secret
        }

        // Hash raw password with SHA-1
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(secret.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> post(endpoint: String, body: String, onSuccess: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val config = getConfig().getOrElse { return@withContext Result.failure(it) }

                val requestBuilder = Request.Builder()
                    .url("${config.baseUrl}/api/v1/$endpoint")
                    .post(body.toRequestBody("application/json".toMediaType()))

                if (config.apiSecret.isNotBlank()) {
                    requestBuilder.addHeader("api-secret", prepareApiSecret(config.apiSecret))
                }

                val response = client.newCall(requestBuilder.build()).execute()

                if (response.isSuccessful) {
                    Result.success(onSuccess(response.body?.string() ?: ""))
                } else {
                    Result.failure(Exception("Upload fehlgeschlagen: ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "POST $endpoint failed", e)
                Result.failure(e)
            }
        }

    private suspend fun <T> get(endpoint: String, onSuccess: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val config = getConfig().getOrElse { return@withContext Result.failure(it) }

                val requestBuilder = Request.Builder()
                    .url("${config.baseUrl}/api/v1/$endpoint")
                    .get()

                if (config.apiSecret.isNotBlank()) {
                    requestBuilder.addHeader("api-secret", prepareApiSecret(config.apiSecret))
                }

                val response = client.newCall(requestBuilder.build()).execute()

                if (response.isSuccessful) {
                    Result.success(onSuccess(response.body?.string() ?: ""))
                } else {
                    Result.failure(Exception("GET fehlgeschlagen: ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "GET $endpoint failed", e)
                Result.failure(e)
            }
        }

    // ════════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════════

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val config = getConfig().getOrElse { return@withContext Result.failure(it) }

            val request = Request.Builder()
                .url("${config.baseUrl}/api/v1/status")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Server antwortet mit Status ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection failed", e)
            Result.failure(e)
        }
    }

    suspend fun uploadReading(reading: GlucoseReading): Result<String> {
        val entry = reading.toNightscoutEntry()
        val body = json.encodeToString(listOf(entry))
        return post("entries", body) { it }
    }

    suspend fun uploadReadings(readings: List<GlucoseReading>): Result<Int> {
        if (readings.isEmpty()) return Result.success(0)
        val entries = readings.map { it.toNightscoutEntry() }
        val body = json.encodeToString(entries)
        return post("entries", body) { readings.size }
    }

    suspend fun uploadDeviceStatus(reading: GlucoseReading): Result<String> {
        val deviceStatus = reading.toDeviceStatus()
        val body = json.encodeToString(listOf(deviceStatus))
        return post("devicestatus", body) { "Device status uploaded" }
    }

    suspend fun uploadTreatment(treatment: NightscoutTreatment): Result<String> {
        val body = json.encodeToString(treatment)
        return post("treatments", body) { "Treatment uploaded" }
    }

    suspend fun uploadReadingWithExtras(reading: GlucoseReading): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val readingResult = uploadReading(reading)
                if (readingResult.isFailure) {
                    return@withContext readingResult
                }

                uploadDeviceStatus(reading)
                detectAndUploadEvents(reading)

                Result.success("Reading, device status, and events uploaded")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ════════════════════════════════════════════════════════════════════════════
    // SAGE (Sensor Age) Management
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Get the latest sensor start/change timestamp from Nightscout
     * Returns the timestamp in milliseconds, or null if not found
     */
    suspend fun getLatestSensorStart(): Result<Long?> {
        return get("treatments?find[eventType][\$regex]=Sensor&count=1") { responseBody ->
            try {
                // Parse JSON array response: [{"created_at": "2025-01-10T12:00:00.000Z", ...}]
                val treatments = json.decodeFromString<List<Map<String, kotlinx.serialization.json.JsonElement>>>(responseBody)
                if (treatments.isEmpty()) {
                    Log.d(TAG, "No sensor treatments found in Nightscout")
                    return@get null
                }

                val treatment = treatments.first()
                val createdAt = treatment["created_at"]?.toString()?.trim('"')
                    ?: treatment["date"]?.toString()?.trim('"')

                if (createdAt != null) {
                    // Parse ISO date string to timestamp
                    val timestamp = parseIsoDate(createdAt)
                    Log.d(TAG, "Latest NS sensor start: $createdAt (${timestamp}ms)")
                    timestamp
                } else {
                    Log.w(TAG, "No date found in sensor treatment")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing sensor treatment response", e)
                null
            }
        }
    }

    /**
     * Upload a new Sensor Start treatment to Nightscout
     * @param sensorStartTime The timestamp when the sensor was inserted (in milliseconds)
     * @param sensorSerial Optional sensor serial number
     */
    suspend fun uploadSensorStart(sensorStartTime: Long, sensorSerial: String? = null): Result<String> {
        val treatment = NightscoutTreatment(
            eventType = "Sensor Start",
            created_at = isoFormat.format(Date(sensorStartTime)),
            date = sensorStartTime,
            device = "loop://CamAPSFX-ScreenReader",
            app = "AndroidAPS",
            enteredBy = "CamAPSFX-ScreenReader",
            isValid = true,
            notes = sensorSerial?.let { "Freestyle Libre 3 Plus - $it" } ?: "Sensor von CamAPS FX"
        )
        val body = json.encodeToString(treatment)
        return post("treatments", body) { "Sensor Start uploaded" }
    }

    /**
     * Check if SAGE in Nightscout matches app sensor age and update if needed
     * @param appSensorStartTime The sensor start time from CamAPS FX (in milliseconds)
     * @param toleranceHours Maximum allowed difference (default 1.5 hours)
     * @param sensorSerial Optional sensor serial number for notes
     * @return Result with action taken: "updated", "in_sync", or error
     */
    suspend fun checkAndUpdateSAGE(
        appSensorStartTime: Long,
        toleranceHours: Double = 1.5,
        sensorSerial: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val nsResult = getLatestSensorStart()
            val nsSensorTime = nsResult.getOrNull()

            val toleranceMs = (toleranceHours * 60 * 60 * 1000).toLong()

            if (nsSensorTime == null) {
                // No SAGE in Nightscout - upload current
                Log.d(TAG, "No SAGE in Nightscout, uploading from app")
                return@withContext uploadSensorStart(appSensorStartTime, sensorSerial)
                    .map { "uploaded (no previous SAGE)" }
            }

            val diff = kotlin.math.abs(appSensorStartTime - nsSensorTime)
            val diffHours = diff / (1000.0 * 60 * 60)

            Log.d(TAG, "SAGE comparison: NS=${Date(nsSensorTime)}, App=${Date(appSensorStartTime)}, diff=${diffHours}h")

            if (diff > toleranceMs) {
                // Difference too large - update Nightscout
                Log.d(TAG, "SAGE diff ${diffHours}h > ${toleranceHours}h tolerance, updating NS")
                return@withContext uploadSensorStart(appSensorStartTime, sensorSerial)
                    .map { "updated (diff was ${String.format("%.1f", diffHours)}h)" }
            }

            Log.d(TAG, "SAGE in sync (diff ${String.format("%.1f", diffHours)}h within tolerance)")
            Result.success("in_sync")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking SAGE", e)
            Result.failure(e)
        }
    }

    private fun parseIsoDate(dateStr: String): Long? {
        return try {
            // Handle both "2025-01-10T12:00:00.000Z" and epoch milliseconds
            if (dateStr.all { it.isDigit() }) {
                dateStr.toLongOrNull()
            } else {
                isoFormat.parse(dateStr)?.time
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing date: $dateStr", e)
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // IAGE (Insulin Age) Management
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Get latest insulin/reservoir change from Nightscout
     */
    suspend fun getLatestInsulinChange(): Result<Long?> {
        return get("treatments?find[eventType][\$regex]=Insulin&count=1") { responseBody ->
            try {
                val treatments = json.decodeFromString<List<Map<String, kotlinx.serialization.json.JsonElement>>>(responseBody)
                if (treatments.isEmpty()) {
                    Log.d(TAG, "No insulin treatments found in Nightscout")
                    return@get null
                }

                val treatment = treatments.first()
                val createdAt = treatment["created_at"]?.toString()?.trim('"')
                    ?: treatment["date"]?.toString()?.trim('"')

                if (createdAt != null) {
                    val timestamp = parseIsoDate(createdAt)
                    Log.d(TAG, "Latest NS insulin change: $createdAt (${timestamp}ms)")
                    timestamp
                } else {
                    Log.w(TAG, "No date found in insulin treatment")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing insulin treatment response", e)
                null
            }
        }
    }

    /**
     * Upload insulin/reservoir change to Nightscout
     */
    suspend fun uploadInsulinChange(fillTime: Long): Result<String> {
        val treatment = NightscoutTreatment(
            eventType = "Insulin Change",
            created_at = isoFormat.format(Date(fillTime)),
            date = fillTime,
            device = "loop://CamAPSFX-ScreenReader",
            app = "AndroidAPS",
            enteredBy = "CamAPSFX-ScreenReader",
            isValid = true,
            notes = "Reservoir gefüllt"
        )
        val body = json.encodeToString(treatment)
        return post("treatments", body) { "Insulin Change uploaded" }
    }

    /**
     * Check IAGE and update Nightscout if difference exceeds tolerance
     */
    suspend fun checkAndUpdateIAGE(
        appFillTime: Long,
        toleranceHours: Double = 1.5
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val nsResult = getLatestInsulinChange()
            val nsFillTime = nsResult.getOrNull()

            val toleranceMs = (toleranceHours * 60 * 60 * 1000).toLong()

            if (nsFillTime == null) {
                // No IAGE in Nightscout - upload current
                Log.d(TAG, "No IAGE in Nightscout, uploading from app")
                return@withContext uploadInsulinChange(appFillTime)
                    .map { "uploaded (no previous IAGE)" }
            }

            val diff = kotlin.math.abs(appFillTime - nsFillTime)
            val diffHours = diff / (1000.0 * 60 * 60)

            Log.d(TAG, "IAGE comparison: NS=${Date(nsFillTime)}, App=${Date(appFillTime)}, diff=${diffHours}h")

            if (diff > toleranceMs) {
                // Difference too large - update Nightscout
                Log.d(TAG, "IAGE diff ${diffHours}h > ${toleranceHours}h tolerance, updating NS")
                return@withContext uploadInsulinChange(appFillTime)
                    .map { "updated (diff was ${String.format("%.1f", diffHours)}h)" }
            }

            Log.d(TAG, "IAGE in sync (diff ${String.format("%.1f", diffHours)}h within tolerance)")
            Result.success("in_sync")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking IAGE", e)
            Result.failure(e)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Event Detection
    // ════════════════════════════════════════════════════════════════════════════

    suspend fun detectAndUploadEvents(reading: GlucoseReading): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val uploadedEvents = mutableListOf<String>()

            try {
                // Detect battery change (higher value than before = new battery)
                reading.pumpBattery?.let { current ->
                    lastBatteryPercent?.let { last ->
                        if (current > last + 10) {
                            uploadTreatment(createTreatment(
                                reading, "Pump Battery Change",
                                "Batterie gewechselt: $last% → $current%"
                            ))
                            uploadedEvents.add("Battery change detected")
                        }
                    }
                    lastBatteryPercent = current
                }

                // Detect reservoir change (higher value = refilled)
                reading.reservoir?.let { current ->
                    lastReservoir?.let { last ->
                        if (current > last + 50) {
                            uploadTreatment(createTreatment(
                                reading, "Insulin Change",
                                "Reservoir gewechselt: ${last.toInt()} IE → ${current.toInt()} IE"
                            ))
                            uploadedEvents.add("Reservoir change detected")
                        }
                    }
                    lastReservoir = current
                }

                // Upload recent bolus (< 5 min ago)
                reading.bolusAmount?.let { amount ->
                    if (amount > 0 && reading.bolusMinutesAgo != null && reading.bolusMinutesAgo < 5) {
                        val bolusTime = reading.timestamp - (reading.bolusMinutesAgo * 60 * 1000).toLong()
                        uploadTreatment(createBolusTreatment(bolusTime, amount))
                        uploadedEvents.add("Bolus uploaded: $amount IE")
                    }
                }

                Result.success(uploadedEvents)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ════════════════════════════════════════════════════════════════════════════
    // Data Converters
    // ════════════════════════════════════════════════════════════════════════════

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun GlucoseReading.toNightscoutEntry() = NightscoutEntry(
        sgv = getValueInUnit(GlucoseUnit.MG_DL).toInt(),
        direction = trend.toDirection(),
        date = timestamp,
        dateString = isoFormat.format(Date(timestamp)),
        device = "DiabetesScreenReader/$source"
    )

    private fun GlucoseReading.toDeviceStatus(): NightscoutDeviceStatus {
        val ts = isoFormat.format(Date(timestamp))
        return NightscoutDeviceStatus(
            device = "loop://CamAPSFX-ScreenReader",
            created_at = ts,
            date = timestamp,
            uploaderBattery = 100,
            isCharging = null,
            uploader = UploaderInfo(battery = 100),
            pump = PumpStatus(
                clock = ts,
                battery = pumpBattery?.let { PumpBattery(percent = it, voltage = null) },
                reservoir = reservoir,
                status = PumpStatusInfo(status = "normal", timestamp = ts)
            ),
            openaps = OpenAPSStatus(
                iob = activeInsulin?.let { OpenAPSIOB(iob = it, basaliob = null, bolusiob = it, timestamp = ts) },
                suggested = basalRate?.let { rate ->
                    OpenAPSSuggested(
                        temp = "absolute",
                        bg = getValueInUnit(GlucoseUnit.MG_DL).toInt(),
                        tick = trend.toDirection(),
                        eventualBG = null,
                        insulinReq = 0.0,
                        rate = rate,
                        duration = 30,
                        timestamp = ts,
                        reason = "CamAPS FX current rate"
                    )
                },
                enacted = basalRate?.let { rate ->
                    OpenAPSEnacted(
                        temp = "absolute",
                        bg = getValueInUnit(GlucoseUnit.MG_DL).toInt(),
                        tick = trend.toDirection(),
                        rate = rate,
                        duration = 30,
                        timestamp = ts,
                        reason = "CamAPS FX current rate"
                    )
                }
            )
        )
    }

    private fun createTreatment(reading: GlucoseReading, eventType: String, notes: String) =
        NightscoutTreatment(
            eventType = eventType,
            created_at = isoFormat.format(Date(reading.timestamp)),
            date = reading.timestamp,
            device = "loop://CamAPSFX-ScreenReader",
            app = "AndroidAPS",
            enteredBy = "CamAPSFX-ScreenReader",
            isValid = true,
            notes = notes
        )

    private fun createBolusTreatment(timestamp: Long, amount: Double) =
        NightscoutTreatment(
            eventType = "Correction Bolus",
            created_at = isoFormat.format(Date(timestamp)),
            date = timestamp,
            device = "loop://CamAPSFX-ScreenReader",
            app = "AndroidAPS",
            enteredBy = "CamAPSFX-ScreenReader",
            isValid = true,
            insulin = amount,
            type = "NORMAL",
            isBasalInsulin = false,
            notes = "Bolus von CamAPS FX"
        )

    private fun GlucoseTrend.toDirection(): String = when (this) {
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
