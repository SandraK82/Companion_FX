package com.diabetesscreenreader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durable, idempotent event discovered in CamAPS FX.
 *
 * This is deliberately separate from [GlucoseReading].  A reading is a
 * screen observation; an event is a treatment or device change that must
 * survive a sleeping phone, a process restart, and a failed upload.
 */
@Entity(
    tableName = "companion_events",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["state", "eventTimestamp"])
    ]
)
data class CompanionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fingerprint: String,
    val eventType: String,
    val eventTimestamp: Long,
    val firstSeenAt: Long,
    val amount: Double? = null,
    val carbs: Double? = null,
    val rate: Double? = null,
    val durationMinutes: Long? = null,
    val notes: String? = null,
    val state: String = "pending",
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val serverId: String? = null,
    val lastError: String? = null,
    // The event remains provisional until the official Glooko record matches.
    val reconciliationState: String = "provisional",
    // Provenance and confidence are kept with the event so Nocturne and Home
    // Assistant can distinguish a drawer observation from a status edge or a
    // user-confirmed candidate.
    @ColumnInfo(defaultValue = "'legacy'")
    val source: String = SOURCE_LEGACY,
    @ColumnInfo(defaultValue = "0")
    val observedAt: Long = firstSeenAt,
    @ColumnInfo(defaultValue = "1.0")
    val confidence: Double = 1.0,
    val correlationKey: String? = null,
    @ColumnInfo(defaultValue = "'not_required'")
    val confirmationState: String = CONFIRMATION_NOT_REQUIRED,
    @ColumnInfo(defaultValue = "1")
    val uploadAllowed: Boolean = true,
    // The first active-device observation is a baseline for inventory. It may
    // populate Nocturne's device age but must not consume Home Assistant stock.
    @ColumnInfo(defaultValue = "0")
    val isBaseline: Boolean = false
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_UPLOADED = "uploaded"
        const val STATE_FAILED = "failed"

        const val RECONCILIATION_PROVISIONAL = "provisional"
        const val RECONCILIATION_RECONCILED = "reconciled"
        const val RECONCILIATION_CONFLICT = "conflict"

        const val CONFIRMATION_NOT_REQUIRED = "not_required"
        const val CONFIRMATION_PENDING = "pending"
        const val CONFIRMATION_CONFIRMED = "confirmed"
        const val CONFIRMATION_REJECTED = "rejected"

        const val SOURCE_LEGACY = "legacy"
        const val SOURCE_CAMAPS_DRAWER_OCR = "camaps_drawer_ocr"
        const val SOURCE_CAMAPS_STATUS = "camaps_status"
        const val SOURCE_GLOOKO_RECONCILIATION = "glooko_reconciliation"
        const val SOURCE_INFERRED_AUTO_MODE = "inferred_auto_mode"
        const val SOURCE_HOME_ASSISTANT = "home_assistant"

        const val TYPE_BOLUS = "bolus"
        const val TYPE_CARB = "carb"
        const val TYPE_MEAL_BOLUS = "meal_bolus"
        const val TYPE_TEMP_BASAL = "temp_basal"
        const val TYPE_SENSOR_START = "sensor_start"
        const val TYPE_INSULIN_CHANGE = "insulin_change"
        const val TYPE_SITE_CHANGE = "site_change"
        const val TYPE_PUMP_BATTERY_CHANGE = "pump_battery_change"
    }
}

/**
 * Durable state used for edge detection of values that CamAPS exposes as a
 * current status rather than as an event (for example reservoir and basal
 * rate).  It is separate from event rows so the first observation is a
 * baseline and does not create a fake treatment.
 */
@Entity(tableName = "companion_observation_state")
data class CompanionObservationStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val observedAt: Long
)
