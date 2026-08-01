package com.diabetesscreenreader.data

import android.util.Log
import com.diabetesscreenreader.network.NightscoutApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class GlucoseRepository(
    private val glucoseDao: GlucoseDao,
    private val companionEventDao: CompanionEventDao,
    private val nightscoutApi: NightscoutApi,
    private val preferencesManager: PreferencesManager
) {

    companion object {
        private const val TAG = "GlucoseRepository"
        private const val EVENT_RECOVERY_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val BASAL_STATE_KEY = "last_basal_rate"
        private const val RESERVOIR_STATE_KEY = "last_reservoir"
        private const val BATTERY_STATE_KEY = "last_pump_battery"
        private const val SENSOR_START_STATE_KEY = "last_sensor_start"
        private const val INSULIN_FILL_STATE_KEY = "last_insulin_fill"
        private const val BOLUS_DEDUPE_WINDOW_MS = 3 * 60 * 1000L
        private const val DEVICE_AGE_MAX_AGE_MS = 31L * 24 * 60 * 60 * 1000L
        private const val DEVICE_AGE_JITTER_WINDOW_MS = 30 * 60 * 1000L
        private const val REFILL_RECONCILIATION_WINDOW_MS = 6 * 60 * 60 * 1000L
        private const val AGE_FINGERPRINT_BUCKET_MS = 15 * 60 * 1000L
    }

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

    suspend fun insertReadingWithoutUpload(reading: GlucoseReading): Long {
        return glucoseDao.insert(reading)
    }

    private suspend fun uploadToNightscout(reading: GlucoseReading) {
        Log.d(TAG, "Uploading reading ${reading.id} to Nightscout...")
        try {
            // Upload the reading and CamAPS device status here; treatment and
            // device-change rows are flushed separately from the durable
            // ledger below.
            val result = nightscoutApi.uploadReadingWithExtras(reading)
            if (result.isSuccess) {
                Log.d(TAG, "Nightscout upload successful for reading ${reading.id}")
                glucoseDao.markAsUploaded(reading.id, "uploaded")
                val eventResult = flushPendingCompanionEvents()
                eventResult.onFailure { error ->
                    Log.w(TAG, "Companion events remain queued: ${error.message}")
                }
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

        // Flush durable treatment/device events independently of the glucose
        // queue. This keeps a failed treatment upload retryable without
        // making the glucose stream appear successful.
        val eventResult = flushPendingCompanionEvents()
        val unuploaded = glucoseDao.getUnuploadedReadings()
        if (unuploaded.isEmpty()) {
            return eventResult
        }

        val readingResult = nightscoutApi.uploadReadings(unuploaded)
        if (readingResult.isSuccess) {
            unuploaded.forEach { reading ->
                glucoseDao.markAsUploaded(reading.id, "bulk-upload")
            }
            preferencesManager.setLastSyncTime(System.currentTimeMillis())
        }

        return if (readingResult.isFailure) {
            readingResult
        } else if (eventResult.isFailure) {
            eventResult
        } else {
            Result.success(readingResult.getOrDefault(0) + eventResult.getOrDefault(0))
        }
    }

    /**
     * Persist a discovered event before attempting any network operation.
     *
     * The dialog exposes a bolus age rounded to whole minutes, so its
     * reconstructed timestamp can jitter slightly between reads. Stable
     * fingerprints still handle exact retries; this short amount/time lookup
     * handles that presentation jitter without merging boluses far apart.
     */
    suspend fun recordEvent(event: CompanionEventEntity): Long {
        if (event.eventType == CompanionEventEntity.TYPE_BOLUS && event.amount != null) {
            val nearby = companionEventDao.findRecentByTypeAndAmount(
                eventType = event.eventType,
                amount = event.amount,
                eventTimestamp = event.eventTimestamp,
                windowMs = BOLUS_DEDUPE_WINDOW_MS
            )
            if (nearby != null) {
                Log.d(TAG, "Ignoring bolus timestamp jitter; existing event id=${nearby.id} is within the dedupe window")
                return nearby.id
            }
        }
        return companionEventDao.insertIfNew(event)
    }

    /**
     * Turn a CamAPS screen observation into durable event rows.  The first
     * observation of current basal/reservoir/battery values establishes a
     * baseline; only changes after that are treated as events.
     */
    suspend fun recordEventsFromReading(reading: GlucoseReading) {
        val now = System.currentTimeMillis()

        reading.bolusAmount?.let { amount ->
            val ageMinutes = reading.bolusMinutesAgo
            if (amount > 0 && ageMinutes != null) {
                val eventTimestamp = reading.timestamp - ageMinutes * 60_000L
                if (isWithinRecoveryWindow(eventTimestamp, now)) {
                    recordEvent(
                        CompanionEventEntity(
                            fingerprint = fingerprint(
                                CompanionEventEntity.TYPE_BOLUS,
                                eventTimestamp,
                                amount = amount
                            ),
                            eventType = CompanionEventEntity.TYPE_BOLUS,
                            eventTimestamp = eventTimestamp,
                            firstSeenAt = now,
                            amount = amount,
                            notes = "CamAPS FX provisional bolus",
                            source = CompanionEventEntity.SOURCE_CAMAPS_STATUS,
                            observedAt = now,
                            confidence = 0.75
                        )
                    )
                }
            }
        }

        reading.basalRate?.let { rate ->
            val previous = companionEventDao.getObservation(BASAL_STATE_KEY)?.value?.toDoubleOrNull()
            if (previous != null && kotlin.math.abs(previous - rate) > 0.001) {
                recordEvent(
                    CompanionEventEntity(
                        fingerprint = fingerprint(CompanionEventEntity.TYPE_TEMP_BASAL, reading.timestamp, rate = rate),
                        eventType = CompanionEventEntity.TYPE_TEMP_BASAL,
                        eventTimestamp = reading.timestamp,
                        firstSeenAt = now,
                        rate = rate,
                        durationMinutes = 60,
                        notes = "CamAPS FX provisional basal-rate change",
                        source = CompanionEventEntity.SOURCE_CAMAPS_STATUS,
                        observedAt = now,
                        confidence = 0.75
                    )
                )
            }
            companionEventDao.saveObservation(
                CompanionObservationStateEntity(BASAL_STATE_KEY, rate.toString(), now)
            )
        }

        reading.reservoir?.let { reservoir ->
            val previous = companionEventDao.getObservation(RESERVOIR_STATE_KEY)?.value?.toDoubleOrNull()
            if (previous != null && reservoir > previous + 50.0) {
                // A drawer refill observation is authoritative. If it was
                // already seen nearby, do not create a second stock event from
                // the reservoir edge.
                val nearbyRefill = companionEventDao.findRecentByType(
                    eventType = CompanionEventEntity.TYPE_INSULIN_CHANGE,
                    eventTimestamp = reading.timestamp,
                    windowMs = REFILL_RECONCILIATION_WINDOW_MS
                )
                if (nearbyRefill == null) {
                    recordEvent(
                        CompanionEventEntity(
                            fingerprint = fingerprint(CompanionEventEntity.TYPE_INSULIN_CHANGE, reading.timestamp, amount = reservoir),
                            eventType = CompanionEventEntity.TYPE_INSULIN_CHANGE,
                            eventTimestamp = reading.timestamp,
                            firstSeenAt = now,
                            amount = reservoir,
                            notes = "CamAPS FX reservoir increase observed",
                            source = CompanionEventEntity.SOURCE_CAMAPS_STATUS,
                            observedAt = now,
                            confidence = 0.75,
                            correlationKey = refillCorrelationKey(reading.timestamp)
                        )
                    )
                }
            }
            companionEventDao.saveObservation(
                CompanionObservationStateEntity(RESERVOIR_STATE_KEY, reservoir.toString(), now)
            )
        }

        reading.pumpBattery?.let { battery ->
            val previous = companionEventDao.getObservation(BATTERY_STATE_KEY)?.value?.toIntOrNull()
            if (previous != null && battery > previous + 10) {
                recordEvent(
                    CompanionEventEntity(
                        fingerprint = fingerprint(CompanionEventEntity.TYPE_PUMP_BATTERY_CHANGE, reading.timestamp, amount = battery.toDouble()),
                        eventType = CompanionEventEntity.TYPE_PUMP_BATTERY_CHANGE,
                        eventTimestamp = reading.timestamp,
                        firstSeenAt = now,
                        amount = battery.toDouble(),
                        notes = "CamAPS FX pump battery replaced ($previous% to $battery%)",
                        source = CompanionEventEntity.SOURCE_CAMAPS_STATUS,
                        observedAt = now,
                        confidence = 0.75
                    )
                )
            }
            companionEventDao.saveObservation(
                CompanionObservationStateEntity(BATTERY_STATE_KEY, battery.toString(), now)
            )
        }
    }

    suspend fun recordGraphTreatment(treatment: com.diabetesscreenreader.service.CamAPSFXReader.GraphTreatment) {
        val now = System.currentTimeMillis()
        if (!treatment.hasBoth && !treatment.hasCarbs && !treatment.hasInsulin) return
        val timestamp = treatment.timestamp
        if (!isWithinRecoveryWindow(timestamp, now)) return

        // Keep insulin and carbohydrate facts as separate canonical Nocturne
        // records. A combined graph marker and a dialog bolus can then
        // reconcile independently instead of producing a duplicate
        // `meal_bolus` plus `bolus` representation of the same delivery.
        if (treatment.hasInsulin) {
            val amount = treatment.insulinUnits!!
            recordEvent(
                CompanionEventEntity(
                    fingerprint = fingerprint(CompanionEventEntity.TYPE_BOLUS, timestamp, amount = amount),
                    eventType = CompanionEventEntity.TYPE_BOLUS,
                    eventTimestamp = timestamp,
                    firstSeenAt = now,
                    amount = amount,
                    notes = "CamAPS FX graph insulin marker (OCR; provisional)",
                    source = CompanionEventEntity.SOURCE_CAMAPS_STATUS,
                    observedAt = now,
                    confidence = 0.55
                )
            )
        }
        if (treatment.hasCarbs) {
            val carbs = treatment.carbsGrams!!.toDouble()
            recordEvent(
                CompanionEventEntity(
                    fingerprint = fingerprint(CompanionEventEntity.TYPE_CARB, timestamp, carbs = carbs),
                    eventType = CompanionEventEntity.TYPE_CARB,
                    eventTimestamp = timestamp,
                    firstSeenAt = now,
                    carbs = carbs,
                    notes = "CamAPS FX graph carbohydrate marker (OCR; provisional)",
                    source = CompanionEventEntity.SOURCE_CAMAPS_STATUS,
                    observedAt = now,
                    confidence = 0.55
                )
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun recordSensorStart(
        sensorStartTime: Long,
        serial: String? = null,
        observedAt: Long = System.currentTimeMillis(),
        source: String = CompanionEventEntity.SOURCE_CAMAPS_DRAWER_OCR,
        confidence: Double = 0.9
    ) {
        // The serial parameter is retained for source compatibility but is
        // intentionally ignored. Device/user identifiers never leave the
        // phone or enter event notes.
        if (!isWithinDeviceAgeWindow(sensorStartTime, observedAt)) return

        val previous = companionEventDao.getObservation(SENSOR_START_STATE_KEY)?.value?.toLongOrNull()
        if (previous != null && kotlin.math.abs(previous - sensorStartTime) <= DEVICE_AGE_JITTER_WINDOW_MS) {
            companionEventDao.saveObservation(
                CompanionObservationStateEntity(SENSOR_START_STATE_KEY, sensorStartTime.toString(), observedAt)
            )
            return
        }

        val baseline = previous == null
        val fingerprint = "companionfx:sensor-start:${sensorStartTime / AGE_FINGERPRINT_BUCKET_MS}"
        val nearby = companionEventDao.findRecentByType(
            eventType = CompanionEventEntity.TYPE_SENSOR_START,
            eventTimestamp = sensorStartTime,
            windowMs = DEVICE_AGE_JITTER_WINDOW_MS
        )
        if (nearby == null) {
            recordEvent(
                CompanionEventEntity(
                    fingerprint = fingerprint,
                    eventType = CompanionEventEntity.TYPE_SENSOR_START,
                    eventTimestamp = sensorStartTime,
                    firstSeenAt = observedAt,
                    notes = "CamAPS FX sensor insertion (${if (baseline) "baseline" else "observed"})",
                    source = source,
                    observedAt = observedAt,
                    confidence = confidence.coerceIn(0.0, 1.0),
                    correlationKey = "sensor-start:${sensorStartTime / AGE_FINGERPRINT_BUCKET_MS}",
                    isBaseline = baseline
                )
            )
        }
        companionEventDao.saveObservation(
            CompanionObservationStateEntity(SENSOR_START_STATE_KEY, sensorStartTime.toString(), observedAt)
        )
    }

    suspend fun recordInsulinChange(
        fillTime: Long,
        observedAt: Long = System.currentTimeMillis(),
        source: String = CompanionEventEntity.SOURCE_CAMAPS_DRAWER_OCR,
        confidence: Double = 0.9
    ) {
        if (!isWithinDeviceAgeWindow(fillTime, observedAt)) return

        val previous = companionEventDao.getObservation(INSULIN_FILL_STATE_KEY)?.value?.toLongOrNull()
        if (previous != null && kotlin.math.abs(previous - fillTime) <= DEVICE_AGE_JITTER_WINDOW_MS) {
            companionEventDao.saveObservation(
                CompanionObservationStateEntity(INSULIN_FILL_STATE_KEY, fillTime.toString(), observedAt)
            )
            return
        }

        val baseline = previous == null
        val fingerprint = "companionfx:insulin-change:${fillTime / AGE_FINGERPRINT_BUCKET_MS}"
        val nearby = companionEventDao.findRecentByType(
            eventType = CompanionEventEntity.TYPE_INSULIN_CHANGE,
            eventTimestamp = fillTime,
            windowMs = REFILL_RECONCILIATION_WINDOW_MS
        )
        if (nearby == null) {
            recordEvent(
                CompanionEventEntity(
                    fingerprint = fingerprint,
                    eventType = CompanionEventEntity.TYPE_INSULIN_CHANGE,
                    eventTimestamp = fillTime,
                    firstSeenAt = observedAt,
                    notes = "CamAPS FX reservoir refill (${if (baseline) "baseline" else "observed"})",
                    source = source,
                    observedAt = observedAt,
                    confidence = confidence.coerceIn(0.0, 1.0),
                    correlationKey = refillCorrelationKey(fillTime),
                    isBaseline = baseline
                )
            )
        }
        companionEventDao.saveObservation(
            CompanionObservationStateEntity(INSULIN_FILL_STATE_KEY, fillTime.toString(), observedAt)
        )
    }

    suspend fun flushPendingCompanionEvents(limit: Int = 50): Result<Int> {
        if (!preferencesManager.nightscoutEnabled.first()) return Result.success(0)
        var uploaded = 0
        var firstFailure: Throwable? = null
        for (event in companionEventDao.getPending(limit)) {
            companionEventDao.markAttempt(event.id, System.currentTimeMillis())
            val result = nightscoutApi.uploadCompanionEvent(event)
            if (result.isSuccess) {
                companionEventDao.markUploaded(event.id, result.getOrNull())
                uploaded++
            } else {
                val error = result.exceptionOrNull() ?: Exception("unknown upload error")
                companionEventDao.markFailed(event.id, error.message ?: error.javaClass.simpleName)
                firstFailure = firstFailure ?: error
            }
        }
        return if (firstFailure != null && uploaded == 0) {
            Result.failure(firstFailure)
        } else {
            Result.success(uploaded)
        }
    }

    suspend fun pendingCompanionEventCount(): Int = companionEventDao.pendingCount()

    private fun isWithinRecoveryWindow(eventTimestamp: Long, now: Long): Boolean =
        eventTimestamp <= now + 5 * 60_000L && eventTimestamp >= now - EVENT_RECOVERY_WINDOW_MS

    private fun isWithinDeviceAgeWindow(eventTimestamp: Long, observedAt: Long): Boolean =
        eventTimestamp <= observedAt + 5 * 60_000L &&
            eventTimestamp >= observedAt - DEVICE_AGE_MAX_AGE_MS

    private fun refillCorrelationKey(timestamp: Long): String =
        "refill:${timestamp / (60 * 60 * 1000L)}"

    private fun fingerprint(
        type: String,
        timestamp: Long,
        amount: Double? = null,
        carbs: Double? = null,
        rate: Double? = null
    ): String {
        val minute = timestamp / 60_000L
        val amountPart = amount?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "-"
        val carbsPart = carbs?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "-"
        val ratePart = rate?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "-"
        return "companionfx:$type:$minute:$amountPart:$carbsPart:$ratePart"
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
