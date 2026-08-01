package com.diabetesscreenreader.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerAgeParserTest {

    @Test
    fun parsesCurrentEnglishCamapsDrawer() {
        val result = DrawerAgeParser.parse(
            "Active insulin\n0.4 U\nSince refill\n2d 4h 5min\n" +
                "Since insertion\n5d 6h 58min\nSensor expires\n8d 1h\nLast calibration"
        )

        assertEquals(2 * 24 * 60 * 60 * 1000L + 4 * 60 * 60 * 1000L + 5 * 60 * 1000L, result.refillAgeMs)
        assertEquals(5 * 24 * 60 * 60 * 1000L + 6 * 60 * 60 * 1000L + 58 * 60 * 1000L, result.insertionAgeMs)
        assertEquals(8 * 24 * 60 * 60 * 1000L + 60 * 60 * 1000L, result.expiryInMs)
        assertEquals(3, result.matchedFields.size)
        assertTrue(result.confidence > 0.9)
    }

    @Test
    fun parsesGermanAndFrenchLabelsWithNonBreakingSpaces() {
        val german = DrawerAgeParser.parse("Füllung seit\u00A03 d 2 h\nAnlage seit\n5 Tage 1 Stunde")
        val french = DrawerAgeParser.parse("Remplissage depuis\n6 heures 20 minutes\nInsertion depuis 9 jours")

        assertNotNull(german.refillAgeMs)
        assertNotNull(german.insertionAgeMs)
        assertEquals(6 * 60 * 60 * 1000L + 20 * 60 * 1000L, french.refillAgeMs)
        assertEquals(9 * 24 * 60 * 60 * 1000L, french.insertionAgeMs)
    }

    @Test
    fun rejectsValuesWithoutUnitsAndDoesNotReadIdentifiers() {
        val result = DrawerAgeParser.parse(
            "Companion CGM\nprivate-sensor-id\nSince insertion\n5\nSince refill\n---"
        )

        assertEquals(null, result.insertionAgeMs)
        assertEquals(null, result.refillAgeMs)
        assertTrue(result.matchedFields.isEmpty())
    }

    @Test
    fun parsesMinuteOnlyAndSplitOcrLines() {
        val result = DrawerAgeParser.parse(
            "Since refill\n58 min\nSince insertion 1 day\nSensor session end\n2d 3h"
        )

        assertEquals(58 * 60 * 1000L, result.refillAgeMs)
        assertEquals(24 * 60 * 60 * 1000L, result.insertionAgeMs)
        assertEquals(2 * 24 * 60 * 60 * 1000L + 3 * 60 * 60 * 1000L, result.expiryInMs)
    }
}
