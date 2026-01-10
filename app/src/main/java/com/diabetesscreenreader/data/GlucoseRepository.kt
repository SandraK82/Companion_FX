package com.diabetesscreenreader.data

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

    suspend fun insertReading(reading: GlucoseReading): Long {
        val id = glucoseDao.insert(reading)

        // Auto-upload to Nightscout if enabled
        if (preferencesManager.nightscoutEnabled.first()) {
            uploadToNightscout(reading.copy(id = id))
        }

        return id
    }

    suspend fun insertReadingWithoutUpload(reading: GlucoseReading): Long {
        return glucoseDao.insert(reading)
    }

    private suspend fun uploadToNightscout(reading: GlucoseReading) {
        val result = nightscoutApi.uploadReading(reading)
        if (result.isSuccess) {
            glucoseDao.markAsUploaded(reading.id, result.getOrNull() ?: "")
            preferencesManager.setLastSyncTime(System.currentTimeMillis())
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
}

data class TimeInRangeStats(
    val lowPercent: Double,
    val inRangePercent: Double,
    val highPercent: Double,
    val totalReadings: Int
)
