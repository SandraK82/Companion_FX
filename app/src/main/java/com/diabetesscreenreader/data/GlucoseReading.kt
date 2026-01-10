package com.diabetesscreenreader.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "glucose_readings")
data class GlucoseReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val value: Double,
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
    val trend: GlucoseTrend = GlucoseTrend.FLAT,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "",
    val uploadedToNightscout: Boolean = false,
    val nightscoutId: String? = null
) {
    fun getValueInUnit(targetUnit: GlucoseUnit): Double {
        return when {
            unit == targetUnit -> value
            unit == GlucoseUnit.MG_DL && targetUnit == GlucoseUnit.MMOL_L -> value / 18.0182
            unit == GlucoseUnit.MMOL_L && targetUnit == GlucoseUnit.MG_DL -> value * 18.0182
            else -> value
        }
    }

    fun getFormattedValue(targetUnit: GlucoseUnit = unit): String {
        val convertedValue = getValueInUnit(targetUnit)
        return when (targetUnit) {
            GlucoseUnit.MG_DL -> convertedValue.toInt().toString()
            GlucoseUnit.MMOL_L -> String.format("%.1f", convertedValue)
        }
    }

    fun isInRange(lowThreshold: Double, highThreshold: Double): RangeStatus {
        val mgDlValue = getValueInUnit(GlucoseUnit.MG_DL)
        return when {
            mgDlValue < lowThreshold -> RangeStatus.LOW
            mgDlValue > highThreshold -> RangeStatus.HIGH
            else -> RangeStatus.IN_RANGE
        }
    }
}

enum class GlucoseUnit {
    MG_DL,
    MMOL_L;

    fun getDisplayString(): String = when (this) {
        MG_DL -> "mg/dL"
        MMOL_L -> "mmol/L"
    }
}

enum class GlucoseTrend(val arrow: String, val description: String) {
    DOUBLE_UP("↑↑", "Stark steigend"),
    SINGLE_UP("↑", "Steigend"),
    FORTY_FIVE_UP("↗", "Leicht steigend"),
    FLAT("→", "Stabil"),
    FORTY_FIVE_DOWN("↘", "Leicht fallend"),
    SINGLE_DOWN("↓", "Fallend"),
    DOUBLE_DOWN("↓↓", "Stark fallend"),
    UNKNOWN("?", "Unbekannt");

    companion object {
        fun fromString(value: String): GlucoseTrend {
            return when {
                value.contains("↑↑") || value.contains("DoubleUp") -> DOUBLE_UP
                value.contains("↑") || value.contains("SingleUp") -> SINGLE_UP
                value.contains("↗") || value.contains("FortyFiveUp") -> FORTY_FIVE_UP
                value.contains("→") || value.contains("Flat") -> FLAT
                value.contains("↘") || value.contains("FortyFiveDown") -> FORTY_FIVE_DOWN
                value.contains("↓↓") || value.contains("DoubleDown") -> DOUBLE_DOWN
                value.contains("↓") || value.contains("SingleDown") -> SINGLE_DOWN
                else -> UNKNOWN
            }
        }

        fun fromRateOfChange(ratePerMinute: Double): GlucoseTrend {
            return when {
                ratePerMinute > 3.0 -> DOUBLE_UP
                ratePerMinute > 2.0 -> SINGLE_UP
                ratePerMinute > 1.0 -> FORTY_FIVE_UP
                ratePerMinute > -1.0 -> FLAT
                ratePerMinute > -2.0 -> FORTY_FIVE_DOWN
                ratePerMinute > -3.0 -> SINGLE_DOWN
                else -> DOUBLE_DOWN
            }
        }
    }
}

enum class RangeStatus {
    LOW,
    IN_RANGE,
    HIGH
}

@Serializable
data class NightscoutEntry(
    val type: String = "sgv",
    val sgv: Int,
    val direction: String,
    val date: Long,
    val dateString: String,
    val device: String = "DiabetesScreenReader"
)
