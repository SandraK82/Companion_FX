package com.diabetesscreenreader.data

import android.util.Log
import com.diabetesscreenreader.network.NightscoutApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GlucoseRepository(
    private val glucoseDao: GlucoseDao,
    private val nightscoutApi: NightscoutApi,
    private val preferencesManager: PreferencesManager
) {

    val latestReading: Flow<GlucoseReading?> = glucoseDao.getLatestReadingFlow()

    fun getLatestReadings(limit: Int): Flow<List<GlucoseReading>> =
        glucoseDao.getLatestReadingsFlow(limit)

    fun getReadingsInRange(startTime: Long, endTime: Long): Flow<List<GlucoseReading>> =
        glucoseDao.getReadingsInRangeFlow(startTime, endTime)

    fun getReadingsSince(since: Long): Flow<List<GlucoseReading>> =
        glucoseDao.getReadingsSinceFlow(since)

    fun getUnuploadedReadingsFlow(): Flow<List<GlucoseReading>> =
        glucoseDao.getUnuploadedReadingsFlow()

    fun getAllReadingsFlow(limit: Int): Flow<List<GlucoseReading>> =
        glucoseDao.getAllReadingsFlow(limit)

    suspend fun insertReading(reading: GlucoseReading): Long {
        val id = glucoseDao.insert(reading)
        Log.d(TAG, "Reading saved to database with id=$id")

        // Auto-upload to Nightscout if enabled
        val nightscoutEnabled = preferencesManager.nightscoutEnabled.first()
        Log.d(TAG, "Nightscout enabled: $nightscoutEnabled")

        if (nightscoutEnabled) {
            Log.d(TAG, "Starting Nightscout upload...")
            uploadToNightscout(reading.copy(id = id))
        } else {
            Log.d(TAG, "Nightscout upload skipped (not enabled)")
        }

        return id
    }

    companion object {
        private const val TAG = "GlucoseRepository"
    }

    suspend fun insertReadingWithoutUpload(reading: GlucoseReading): Long {
        return glucoseDao.insert(reading)
    }

    private suspend fun uploadToNightscout(reading: GlucoseReading) {
        Log.d(TAG, "Uploading reading ${reading.id} to Nightscout...")
        try {
            // Use extended upload with device status and event detection
            val result = nightscoutApi.uploadReadingWithExtras(reading)
            if (result.isSuccess) {
                Log.d(TAG, "Nightscout upload successful for reading ${reading.id}")
                glucoseDao.markAsUploaded(reading.id, "uploaded")
                preferencesManager.setLastSyncTime(System.currentTimeMillis())
            } else {
                Log.e(TAG, "Nightscout upload failed: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nightscout upload exception", e)
        }
    }

    suspend fun syncUnuploadedReadings(): Result<Int> {
        if (!preferencesManager.nightscoutEnabled.first()) {
            return Result.success(0)
        }

        val unuploaded = glucoseDao.getUnuploadedReadings()
        if (unuploaded.isEmpty()) {
            return Result.success(0)
        }

        val result = nightscoutApi.uploadReadings(unuploaded)
        if (result.isSuccess) {
            unuploaded.forEach { reading ->
                glucoseDao.markAsUploaded(reading.id, "bulk-upload")
            }
            preferencesManager.setLastSyncTime(System.currentTimeMillis())
        }

        return result
    }

    suspend fun getLatestReadingSync(): GlucoseReading? = glucoseDao.getLatestReading()

    suspend fun getLatestReadingsSync(limit: Int): List<GlucoseReading> =
        glucoseDao.getLatestReadings(limit)

    suspend fun getReadingsInRangeSync(startTime: Long, endTime: Long): List<GlucoseReading> =
        glucoseDao.getReadingsInRange(startTime, endTime)

    suspend fun getAverageGlucose(hoursBack: Int): Double? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (hoursBack * 60 * 60 * 1000L)
        val unit = preferencesManager.getGlucoseUnitSync()
        return glucoseDao.getAverageInRange(startTime, endTime, unit)
    }

    suspend fun getTimeInRange(hoursBack: Int): TimeInRangeStats {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (hoursBack * 60 * 60 * 1000L)
        val readings = glucoseDao.getReadingsInRange(startTime, endTime)

        if (readings.isEmpty()) {
            return TimeInRangeStats(0.0, 0.0, 0.0, 0)
        }

        val lowThreshold = preferencesManager.getLowThresholdSync().toDouble()
        val highThreshold = preferencesManager.getHighThresholdSync().toDouble()

        var lowCount = 0
        var inRangeCount = 0
        var highCount = 0

        readings.forEach { reading ->
            val mgDlValue = reading.getValueInUnit(GlucoseUnit.MG_DL)
            when {
                mgDlValue < lowThreshold -> lowCount++
                mgDlValue > highThreshold -> highCount++
                else -> inRangeCount++
            }
        }

        val total = readings.size.toDouble()
        return TimeInRangeStats(
            lowPercent = (lowCount / total) * 100,
            inRangePercent = (inRangeCount / total) * 100,
            highPercent = (highCount / total) * 100,
            totalReadings = readings.size
        )
    }

    suspend fun cleanupOldData(daysToKeep: Int = 90) {
        val threshold = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        glucoseDao.deleteOldReadings(threshold)
    }

    suspend fun clearUploadQueue(): Result<Int> {
        return try {
            val count = glucoseDao.clearUploadQueue()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class TimeInRangeStats(
    val lowPercent: Double,
    val inRangePercent: Double,
    val highPercent: Double,
    val totalReadings: Int
)
