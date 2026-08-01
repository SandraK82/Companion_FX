package com.diabetesscreenreader.service

import java.util.Locale

/**
 * Parser for the device-age labels rendered in the CamAPS drawer.
 *
 * CamAPS currently draws these values in a custom view. They are therefore
 * available to OCR but not to the accessibility node tree. This class is
 * deliberately pure so that OCR fixtures can exercise it without an Android
 * service or a real phone.
 */
object DrawerAgeParser {

    enum class Field {
        INSERTION,
        REFILL,
        EXPIRY
    }

    data class ParsedAge(
        val insertionAgeMs: Long? = null,
        val refillAgeMs: Long? = null,
        val expiryInMs: Long? = null,
        val matchedFields: Set<Field> = emptySet(),
        val confidence: Double = 0.0
    )

    private val labelVariants = mapOf(
        Field.INSERTION to listOf(
            "since insertion",
            "inserted since",
            "sensor since",
            "insertion depuis",
            "capteur depuis",
            "anlage seit",
            "eingesetzt seit",
            "sensor seit"
        ),
        Field.REFILL to listOf(
            "since refill",
            "refill since",
            "filled since",
            "reservoir since",
            "remplissage depuis",
            "reservoir depuis",
            "réservoir depuis",
            "füllung seit",
            "fuellung seit",
            "reservoir seit"
        ),
        Field.EXPIRY to listOf(
            "sensor expires",
            "sensor expiry",
            "sensor expiration",
            "sensor session end",
            "fin session capteur",
            "ende sensorsitzung",
            "sensor ende"
        )
    )

    /**
     * Parse OCR or accessibility text. Labels and values may be on one line,
     * on adjacent lines, or separated by a small amount of unrelated layout
     * text. Values without a unit are intentionally rejected.
     */
    fun parse(rawText: String): ParsedAge {
        if (rawText.isBlank()) return ParsedAge()

        val normalized = rawText
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace('\r', '\n')
            .replace(Regex("[\\u2010-\\u2015]"), "-")

        val lines = normalized
            .split('\n')
            .map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter { it.isNotBlank() }

        val matches = linkedMapOf<Field, Long>()
        for (index in lines.indices) {
            val line = lines[index]
            val lower = line.lowercase(Locale.ROOT)

            for ((field, labels) in labelVariants) {
                val label = labels.firstOrNull { lower.contains(it) } ?: continue
                val labelStart = lower.indexOf(label)
                val sameLine = line.substring(labelStart + label.length)
                    .trim(' ', ':', '-', '|')

                val candidates = buildList {
                    if (sameLine.isNotBlank()) add(sameLine)
                    // CamAPS usually places the value immediately below the
                    // label. A second line handles OCR block fragmentation.
                    lines.getOrNull(index + 1)?.let { add(it) }
                    lines.getOrNull(index + 2)?.let { add(it) }
                }

                val duration = candidates.firstNotNullOfOrNull(::parseDuration)
                if (duration != null) {
                    matches[field] = duration
                    break
                }
            }
        }

        val confidence = when (matches.size) {
            3 -> 0.95
            2 -> 0.90
            1 -> 0.80
            else -> 0.0
        }

        return ParsedAge(
            insertionAgeMs = matches[Field.INSERTION],
            refillAgeMs = matches[Field.REFILL],
            expiryInMs = matches[Field.EXPIRY],
            matchedFields = matches.keys,
            confidence = confidence
        )
    }

    /** Parse compact and verbose day/hour/minute formats. */
    fun parseDuration(rawText: String): Long? {
        if (rawText.isBlank() || rawText.trim() == "---") return null

        val text = rawText
            .lowercase(Locale.ROOT)
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace(',', '.')

        fun value(pattern: String): Long {
            return Regex(pattern).find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        }

        val days = value("(\\d+)\\s*(?:d|day|days|tag|tage|jours?)\\b")
        val hours = value("(\\d+)\\s*(?:h|hour|hours|stunde|stunden|heure|heures?)\\b")
        val minutes = value("(\\d+)\\s*(?:min|mins|minute|minutes|minuten?)\\b")

        if (days == 0L && hours == 0L && minutes == 0L) return null

        return days * 24 * 60 * 60 * 1000L +
            hours * 60 * 60 * 1000L +
            minutes * 60 * 1000L
    }
}
