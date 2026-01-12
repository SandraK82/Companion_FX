package com.diabetesscreenreader.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseTrend
import com.diabetesscreenreader.data.GlucoseUnit
import kotlinx.coroutines.delay

/**
 * Specialized reader for mylife CamAPS FX app
 * Reads glucose data from main screen and information dialog
 */
class CamAPSFXReader {

    companion object {
        private const val TAG = "CamAPSFXReader"
        private const val PACKAGE_NAME = "com.camdiab.fx.camaps"
    }

    /**
     * Extract glucose data from CamAPS FX app
     * Reads main screen and attempts to open information dialog for additional data
     */
    suspend fun extractData(rootNode: AccessibilityNodeInfo, service: android.accessibilityservice.AccessibilityService? = null): GlucoseReading? {
        try {
            Log.d(TAG, "=== Starting CamAPS FX data extraction ===")

            // First, extract data from main screen
            val mainScreenData = extractMainScreenData(rootNode)
            if (mainScreenData == null) {
                Log.w(TAG, "Could not extract main screen data")
                return null
            }

            Log.d(TAG, "Main screen data extracted: glucose=${mainScreenData.value}, trend=${mainScreenData.trend}")

            // Try to find and click information button (i-button) to get detailed data
            val infoButton = findInfoButton(rootNode)
            if (infoButton != null && service != null) {
                Log.d(TAG, "Found information button, clicking to get detailed data...")
                infoButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // Wait for dialog to open and load content (non-blocking)
                delay(1500)

                // Get the NEW window (dialog) that just opened
                val dialogNode = service.rootInActiveWindow
                if (dialogNode != null) {
                    Log.d(TAG, "Got dialog window node, extracting data...")
                    // Extract data from information dialog
                    val detailedData = extractInformationDialogData(dialogNode)
                    infoButton.recycle()

                    // Close dialog by clicking X button
                    val closeButton = findCloseButton(dialogNode)
                    if (closeButton != null) {
                        Log.d(TAG, "Closing info dialog...")
                        closeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        closeButton.recycle()
                    } else {
                        Log.w(TAG, "Could not find close button")
                    }

                    dialogNode.recycle()

                    // Combine data (deduplication happens in DiabetesAccessibilityService)
                    Log.d(TAG, "Combining main screen data with detailed data: ${detailedData.keys}")

                    return mainScreenData.copy(
                        activeInsulin = detailedData["activeInsulin"],
                        basalRate = detailedData["basalRate"],
                        reservoir = detailedData["reservoir"],
                        pumpBattery = detailedData["pumpBattery"]?.toInt(),
                        bolusAmount = detailedData["bolusAmount"],
                        bolusMinutesAgo = detailedData["bolusMinutesAgo"]?.toInt(),
                        pumpConnectionMinutesAgo = detailedData["pumpConnectionMinutesAgo"]?.toInt(),
                        sensorDataMinutesAgo = detailedData["sensorDataMinutesAgo"]?.toInt(),
                        glucoseTarget = detailedData["glucoseTarget"],
                        insulinToday = detailedData["insulinToday"],
                        insulinYesterday = detailedData["insulinYesterday"]
                    )
                } else {
                    Log.w(TAG, "Could not get dialog window node")
                    infoButton.recycle()
                }
            } else {
                if (infoButton == null) {
                    Log.w(TAG, "Could not find info button, returning main screen data only")
                } else {
                    Log.w(TAG, "Service not provided, cannot read dialog")
                    infoButton.recycle()
                }
            }

            return mainScreenData
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting data", e)
            return null
        }
    }

    /**
     * Extract glucose value and trend from main screen
     * IMPORTANT: Only extract if we're SURE this is the CamAPS FX main screen
     */
    private fun extractMainScreenData(rootNode: AccessibilityNodeInfo): GlucoseReading? {
        val allText = mutableListOf<String>()
        collectAllText(rootNode, allText)

        Log.d(TAG, "All text found on screen (${allText.size} items):")
        allText.forEachIndexed { index, text ->
            Log.d(TAG, "  [$index]: '$text'")
        }

        // SAFETY CHECK 1: Verify this is the CamAPS FX main screen
        // Must have these markers to be considered valid
        val hasUnit = allText.any { it.contains("mg/dL", ignoreCase = true) || it.contains("mg/dl") || it.contains("mmol/L", ignoreCase = true) }
        val hasHilfeButton = findInfoButton(rootNode) != null
        val hasCamAPSMarkers = allText.any {
            it.contains("Auto mode", ignoreCase = true) ||
            it.contains("Boost", ignoreCase = true) ||
            it.contains("Ease-off", ignoreCase = true) ||
            it.contains("mylife CamAPS", ignoreCase = true)
        }

        if (!hasUnit) {
            Log.w(TAG, "SAFETY CHECK FAILED: No unit (mg/dL or mmol/L) found - not CamAPS FX main screen")
            return null
        }

        if (!hasHilfeButton) {
            Log.w(TAG, "SAFETY CHECK FAILED: No Hilfe button found - not CamAPS FX main screen")
            return null
        }

        if (!hasCamAPSMarkers) {
            Log.w(TAG, "SAFETY CHECK FAILED: No CamAPS FX markers found - not CamAPS FX main screen")
            return null
        }

        Log.d(TAG, "✓ SAFETY CHECK PASSED: This is the CamAPS FX main screen")

        // SAFETY CHECK 2: Find glucose value - must be near the unit
        var glucoseValue: Double? = null
        var unit = GlucoseUnit.MG_DL

        // First, find the unit to know what we're looking for
        val unitText = allText.firstOrNull {
            it.contains("mg/dL", ignoreCase = true) || it.contains("mg/dl") || it.contains("mmol/L", ignoreCase = true)
        }

        if (unitText != null) {
            if (unitText.contains("mg/dL", ignoreCase = true) || unitText.contains("mg/dl")) {
                unit = GlucoseUnit.MG_DL
                Log.d(TAG, "Found unit: mg/dL")
            } else if (unitText.contains("mmol/L", ignoreCase = true)) {
                unit = GlucoseUnit.MMOL_L
                Log.d(TAG, "Found unit: mmol/L")
            }
        }

        // CRITICAL SAFETY CHECK: Detect signal loss indicators (multi-language)
        val signalLossIndicators = listOf(
            "---",
            // DE
            "signalverlust", "sensor fehler", "kein signal",
            // EN
            "signal loss", "no signal", "sensor error", "lost signal",
            // FR
            "perte de signal", "pas de signal", "erreur capteur", "signal perdu"
        )
        for (text in allText) {
            val lowerText = text.lowercase()
            for (indicator in signalLossIndicators) {
                if (lowerText.contains(indicator)) {
                    Log.w(TAG, "SAFETY CHECK FAILED: Signal loss detected ('$text') - no valid glucose data")
                    return null
                }
            }
        }

        // Now look for glucose value - a standalone number in the glucose range
        // In CamAPS FX, the glucose value is displayed LARGE and standalone
        val potentialValues = mutableListOf<Int>()

        for (text in allText) {
            // Pattern: standalone number in glucose range
            val valuePattern = Regex("""^\s*(\d{2,3})\s*$""")
            val match = valuePattern.find(text)
            if (match != null) {
                val value = match.groupValues[1].toIntOrNull()
                // CRITICAL: Value must be in valid glucose range AND not zero!
                if (value != null && value > 0 && value >= 40 && value <= 400) {
                    potentialValues.add(value)
                    Log.d(TAG, "Found potential glucose value: $value from text '$text'")
                } else if (value != null && value <= 0) {
                    Log.e(TAG, "REJECTED INVALID VALUE: $value (zero or negative - medically impossible!)")
                } else if (value != null && value < 40) {
                    Log.e(TAG, "REJECTED INVALID VALUE: $value (below minimum 40 mg/dL)")
                } else if (value != null && value > 400) {
                    Log.e(TAG, "REJECTED INVALID VALUE: $value (above maximum 400 mg/dL)")
                }
            }
        }

        // SAFETY CHECK 3: Should have exactly ONE standalone number in glucose range
        if (potentialValues.isEmpty()) {
            Log.w(TAG, "SAFETY CHECK FAILED: No valid glucose value found")
            return null
        }

        if (potentialValues.size > 1) {
            Log.w(TAG, "SAFETY CHECK WARNING: Multiple potential values found: $potentialValues - using first one")
        }

        glucoseValue = potentialValues.first().toDouble()

        // CRITICAL FINAL CHECK: Ensure value is not zero before using it
        if (glucoseValue <= 0) {
            Log.e(TAG, "CRITICAL SAFETY CHECK FAILED: Final glucose value is zero or negative: $glucoseValue - REJECTING!")
            return null
        }

        if (glucoseValue < 40 || glucoseValue > 400) {
            Log.e(TAG, "CRITICAL SAFETY CHECK FAILED: Final glucose value out of range: $glucoseValue - REJECTING!")
            return null
        }

        Log.d(TAG, "✓ Using glucose value: $glucoseValue")

        // Determine trend (looking for arrow or trend indicator)
        val trend = determineTrend(allText)

        Log.d(TAG, "✓ Extracted main screen data: glucose=$glucoseValue, trend=$trend, unit=$unit")

        return GlucoseReading(
            value = glucoseValue,
            unit = unit,
            trend = trend,
            source = PACKAGE_NAME,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Extract detailed data from information dialog
     * Supports German, English, and French CamAPS FX UI
     */
    private fun extractInformationDialogData(rootNode: AccessibilityNodeInfo): Map<String, Double> {
        val data = mutableMapOf<String, Double>()
        val allText = mutableListOf<String>()
        collectAllText(rootNode, allText)

        Log.d(TAG, "=== Information Dialog Text (${allText.size} items) ===")
        allText.forEachIndexed { index, text ->
            Log.d(TAG, "  Dialog[$index]: '$text'")
        }

        for (text in allText) {
            // Active Insulin (DE: Aktives Insulin, EN: Active Insulin, FR: Insuline active)
            extractPattern(text, """(?:Aktives Insulin|Active Insulin|Insuline active):\s*([\d,\.]+)\s*(?:IE|U|UI)""")?.let {
                data["activeInsulin"] = it
            }

            // Basal Rate (DE: Insulinabgaberate, EN: Insulin delivery rate, FR: Debit d'insuline)
            extractPattern(text, """(?:Insulinabgaberate|Insulin delivery rate|Debit d'insuline):\s*([\d,\.]+)\s*(?:IE|U|UI)/h""")?.let {
                data["basalRate"] = it
            }

            // Reservoir (same in all languages)
            extractPattern(text, """Reservoir:\s*([\d,\.]+)\s*(?:IE|U|UI)""")?.let {
                data["reservoir"] = it
            }

            // Pump Battery (DE: Pumpenbatterie, EN: Pump battery, FR: Batterie pompe)
            extractPattern(text, """(?:Pumpenbatterie|Pump battery|Batterie pompe):\s*([\d,\.]+)\s*%""")?.let {
                data["pumpBattery"] = it
            }

            // Bolus parsing - multi-language
            // DE: "Bolus: 2,00 IE vor 31 Minuten" / "Bolus: 11,30 IE 5 h 17 min"
            // EN: "Bolus: 2.00 U 31 minutes ago" / "Bolus: 11.30 U 5 h 17 min ago"
            // FR: "Bolus: 2,00 UI il y a 31 minutes"
            val bolusPatternDE1 = Regex("""Bolus:\s*([\d,\.]+)\s*(?:IE|U|UI)\s*vor\s*(\d+)\s*Minuten""", RegexOption.IGNORE_CASE)
            val bolusPatternEN1 = Regex("""Bolus:\s*([\d,\.]+)\s*(?:IE|U|UI)\s*(\d+)\s*minutes?\s*ago""", RegexOption.IGNORE_CASE)
            val bolusPatternFR1 = Regex("""Bolus:\s*([\d,\.]+)\s*(?:IE|U|UI)\s*il y a\s*(\d+)\s*minutes?""", RegexOption.IGNORE_CASE)
            val bolusPattern2 = Regex("""Bolus:\s*([\d,\.]+)\s*(?:IE|U|UI)\s*(\d+)\s*h\s*(\d+)\s*min""", RegexOption.IGNORE_CASE)
            val bolusPatternNone = Regex("""Bolus:\s*---""", RegexOption.IGNORE_CASE)

            // Try patterns in order, stop at first match
            bolusPatternDE1.find(text)?.let { match ->
                match.groupValues[1].replace(",", ".").toDoubleOrNull()?.let { amount ->
                    data["bolusAmount"] = amount
                    Log.d(TAG, "Captured bolus (DE format): $amount vor ${match.groupValues[2]} Minuten")
                }
                match.groupValues[2].toIntOrNull()?.let { minutes ->
                    data["bolusMinutesAgo"] = minutes.toDouble()
                }
            } ?: bolusPatternEN1.find(text)?.let { match ->
                match.groupValues[1].replace(",", ".").toDoubleOrNull()?.let { amount ->
                    data["bolusAmount"] = amount
                    Log.d(TAG, "Captured bolus (EN format): $amount ${match.groupValues[2]} minutes ago")
                }
                match.groupValues[2].toIntOrNull()?.let { minutes ->
                    data["bolusMinutesAgo"] = minutes.toDouble()
                }
            } ?: bolusPatternFR1.find(text)?.let { match ->
                match.groupValues[1].replace(",", ".").toDoubleOrNull()?.let { amount ->
                    data["bolusAmount"] = amount
                    Log.d(TAG, "Captured bolus (FR format): $amount il y a ${match.groupValues[2]} minutes")
                }
                match.groupValues[2].toIntOrNull()?.let { minutes ->
                    data["bolusMinutesAgo"] = minutes.toDouble()
                }
            } ?: bolusPattern2.find(text)?.let { match ->
                match.groupValues[1].replace(",", ".").toDoubleOrNull()?.let { amount ->
                    data["bolusAmount"] = amount
                    val hours = match.groupValues[2].toIntOrNull() ?: 0
                    val mins = match.groupValues[3].toIntOrNull() ?: 0
                    Log.d(TAG, "Captured bolus (h/min format): $amount ${hours}h ${mins}min")
                    data["bolusMinutesAgo"] = (hours * 60 + mins).toDouble()
                }
            } ?: bolusPatternNone.find(text)?.let {
                Log.d(TAG, "No active bolus (---)")
            }

            // Pump Connection (DE: Pumpenverbindung, EN: Pump connection, FR: Connexion pompe)
            val pumpConnPatternDE1 = Regex("""(?:Pumpenverbindung|Pump connection|Connexion pompe):\s*vor\s*(\d+)\s*Minuten""", RegexOption.IGNORE_CASE)
            val pumpConnPatternDE2 = Regex("""(?:Pumpenverbindung|Pump connection|Connexion pompe):\s*vor\s*einer\s*Minute""", RegexOption.IGNORE_CASE)
            val pumpConnPatternEN = Regex("""(?:Pumpenverbindung|Pump connection|Connexion pompe):\s*(\d+)\s*minutes?\s*ago""", RegexOption.IGNORE_CASE)
            val pumpConnPatternFR = Regex("""(?:Pumpenverbindung|Pump connection|Connexion pompe):\s*il y a\s*(\d+)\s*minutes?""", RegexOption.IGNORE_CASE)

            pumpConnPatternDE1.find(text)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.let { data["pumpConnectionMinutesAgo"] = it }
            } ?: pumpConnPatternDE2.find(text)?.let {
                data["pumpConnectionMinutesAgo"] = 1.0
            } ?: pumpConnPatternEN.find(text)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.let { data["pumpConnectionMinutesAgo"] = it }
            } ?: pumpConnPatternFR.find(text)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.let { data["pumpConnectionMinutesAgo"] = it }
            }

            // Sensor Data (DE: Sensordaten, EN: Sensor data, FR: Donnees capteur)
            val sensorDataPatternDE1 = Regex("""(?:Sensordaten|Sensor data|Donnees capteur):\s*vor\s*(\d+)\s*Minuten""", RegexOption.IGNORE_CASE)
            val sensorDataPatternDE2 = Regex("""(?:Sensordaten|Sensor data|Donnees capteur):\s*vor\s*einer\s*Minute""", RegexOption.IGNORE_CASE)
            val sensorDataPatternEN = Regex("""(?:Sensordaten|Sensor data|Donnees capteur):\s*(\d+)\s*minutes?\s*ago""", RegexOption.IGNORE_CASE)
            val sensorDataPatternFR = Regex("""(?:Sensordaten|Sensor data|Donnees capteur):\s*il y a\s*(\d+)\s*minutes?""", RegexOption.IGNORE_CASE)

            sensorDataPatternDE1.find(text)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.let { data["sensorDataMinutesAgo"] = it }
            } ?: sensorDataPatternDE2.find(text)?.let {
                data["sensorDataMinutesAgo"] = 1.0
            } ?: sensorDataPatternEN.find(text)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.let { data["sensorDataMinutesAgo"] = it }
            } ?: sensorDataPatternFR.find(text)?.let { match ->
                match.groupValues[1].toDoubleOrNull()?.let { data["sensorDataMinutesAgo"] = it }
            }

            // Glucose Target (DE: Glukosezielwert, EN: Glucose target, FR: Cible glycemique)
            // Support both mg/dL and mmol/L
            extractPattern(text, """(?:Glukosezielwert|Glucose target|Cible glycemique):\s*([\d,\.]+)\s*(?:mg/dL|mmol/L)""")?.let {
                data["glucoseTarget"] = it
            }

            // Insulin Today (DE: Insulin heute, EN: Insulin today, FR: Insuline aujourd'hui)
            extractPattern(text, """(?:Insulin heute|Insulin today|Insuline aujourd'hui):\s*([\d,\.]+)\s*(?:IE|U|UI)""")?.let {
                data["insulinToday"] = it
            }

            // Insulin Yesterday (DE: Insulin gestern, EN: Insulin yesterday, FR: Insuline hier)
            extractPattern(text, """(?:Insulin gestern|Insulin yesterday|Insuline hier):\s*([\d,\.]+)\s*(?:IE|U|UI)""")?.let {
                data["insulinYesterday"] = it
            }
        }

        Log.d(TAG, "Extracted information dialog data: $data")
        return data
    }

    /**
     * Find the information button (i-button) in the view hierarchy
     * Supports DE: Hilfe, EN: Help, FR: Aide
     */
    private fun findInfoButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        // Log ALL clickable nodes (not just first 5 levels) to find the button
        if (node.isClickable) {
            Log.d(TAG, "Clickable[depth=$depth]: class=$className, viewId=$viewId, contentDesc='$contentDesc', text='$text'")
        }

        // Look for button with info-related identifiers
        // Multi-language support: DE: Hilfe, EN: Help, FR: Aide
        if (node.isClickable) {
            val matches = contentDesc.contains("info") ||
                    contentDesc.contains("information") ||
                    contentDesc.contains("hilfe") ||  // DE: Hilfe
                    contentDesc.contains("help") ||   // EN: Help
                    contentDesc.contains("aide") ||   // FR: Aide
                    contentDesc.contains("detail") ||
                    text.contains("info") ||
                    text == "i" ||
                    viewId.contains("info")

            if (matches) {
                Log.d(TAG, "✓ FOUND info button: class=$className, viewId=$viewId, contentDesc='$contentDesc', text='$text'")
                return node  // Return the FIRST match (first Hilfe button)
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findInfoButton(child, depth + 1)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }

        return null
    }

    /**
     * Find the close button (X) in the dialog
     * Public wrapper for external access
     */
    fun findCloseButtonPublic(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findCloseButton(node)
    }

    /**
     * Find the close button (X) in the dialog (internal)
     * Multi-language support: DE, EN, FR
     */
    private fun findCloseButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        // Log clickable nodes to help find the close button
        if (node.isClickable && (contentDesc.isNotBlank() || text.isNotBlank() || viewId.isNotBlank())) {
            Log.d(TAG, "Checking close button candidate: class=$className, viewId=$viewId, contentDesc='$contentDesc', text='$text'")
        }

        if (node.isClickable) {
            val matches = contentDesc.contains("close") ||
                    contentDesc.contains("schließen") ||   // DE: close
                    contentDesc.contains("fermer") ||      // FR: close
                    contentDesc.contains("zurück") ||      // DE: back
                    contentDesc.contains("back") ||        // EN: back
                    contentDesc.contains("retour") ||      // FR: back
                    contentDesc.contains("quittieren") ||  // DE: acknowledge
                    contentDesc.contains("acknowledge") || // EN: acknowledge
                    contentDesc.contains("confirmer") ||   // FR: confirm
                    contentDesc.contains("ablehnen") ||    // DE: dismiss
                    contentDesc.contains("dismiss") ||     // EN: dismiss
                    contentDesc.contains("rejeter") ||     // FR: dismiss
                    text == "x" ||
                    text == "×" ||
                    text.contains("schließen") ||
                    text.contains("close") ||
                    text.contains("fermer") ||
                    text.contains("quittieren") ||
                    text.contains("ok") ||
                    viewId.contains("close") ||
                    viewId.contains("back")

            if (matches) {
                Log.d(TAG, "Found close button: class=$className, viewId=$viewId, contentDesc='$contentDesc', text='$text'")
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findCloseButton(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }

        return null
    }

    /**
     * Collect all text content from node tree
     */
    private fun collectAllText(node: AccessibilityNodeInfo, result: MutableList<String>, depth: Int = 0) {
        if (depth > 20) return

        // Log all nodes to see what we're getting
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""

        if (depth < 5) { // Only log first 5 levels to avoid spam
            Log.d(TAG, "Node[depth=$depth]: class=$className, viewId=$viewId, text='$text', desc='$contentDesc', clickable=${node.isClickable}, childCount=${node.childCount}")
        }

        if (text.isNotBlank()) result.add(text)
        if (contentDesc.isNotBlank()) result.add(contentDesc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, result, depth + 1)
            child.recycle()
        }
    }

    /**
     * Determine trend from text content
     */
    private fun determineTrend(texts: List<String>): GlucoseTrend {
        for (text in texts) {
            when {
                text.contains("↑↑") || text.contains("⇈") -> return GlucoseTrend.DOUBLE_UP
                text.contains("↑") || text.contains("⬆") -> return GlucoseTrend.SINGLE_UP
                text.contains("↗") || text.contains("⬈") -> return GlucoseTrend.FORTY_FIVE_UP
                text.contains("→") || text.contains("➡") -> return GlucoseTrend.FLAT
                text.contains("↘") || text.contains("⬊") -> return GlucoseTrend.FORTY_FIVE_DOWN
                text.contains("↓↓") || text.contains("⇊") -> return GlucoseTrend.DOUBLE_DOWN
                text.contains("↓") || text.contains("⬇") -> return GlucoseTrend.SINGLE_DOWN
            }
        }
        return GlucoseTrend.FLAT
    }

    /**
     * Extract number from text using regex pattern
     */
    private fun extractPattern(text: String, pattern: String): Double? {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        val numberStr = match.groupValues.getOrNull(1) ?: return null
        return numberStr.replace(",", ".").toDoubleOrNull()
    }

    /**
     * Parse duration strings to milliseconds
     * Supports multiple formats and languages:
     * - Compact: "5d 6h 58min"
     * - DE: "5 Tage 6 Stunden", "5 Tag 6 Stunde"
     * - EN: "5 days 6 hours", "5 day 6 hour"
     * - FR: "5 jours 6 heures", "5 jour 6 heure"
     */
    private fun parseDuration(text: String): Long? {
        if (text.isBlank() || text == "---") return null

        var totalMs = 0L

        // Pattern 1: "5d 6h 58min" (compact - works in all languages)
        val compactPattern = Regex("""(\d+)d\s*(?:(\d+)h)?\s*(?:(\d+)min)?""", RegexOption.IGNORE_CASE)
        compactPattern.find(text)?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: 0
            val hours = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val minutes = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

            totalMs = (days * 24 * 60 * 60 * 1000L) +
                      (hours * 60 * 60 * 1000L) +
                      (minutes * 60 * 1000L)

            Log.d(TAG, "Parsed compact duration '$text': ${days}d ${hours}h ${minutes}min = ${totalMs}ms")
            return totalMs
        }

        // Pattern 2: Multi-language verbose format (DE: Tage/Stunden, EN: days/hours, FR: jours/heures)
        val verboseDaysPattern = Regex("""(\d+)\s*(?:Tage?|days?|jours?)(?:\s+(\d+)\s*(?:Stunden?|hours?|heures?))?""", RegexOption.IGNORE_CASE)
        verboseDaysPattern.find(text)?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: 0
            val hours = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0

            totalMs = (days * 24 * 60 * 60 * 1000L) + (hours * 60 * 60 * 1000L)

            Log.d(TAG, "Parsed verbose duration '$text': ${days}d ${hours}h = ${totalMs}ms")
            return totalMs
        }

        // Pattern 3: Just hours and minutes "6h 58min" or "6 hours 58 minutes"
        val hoursMinPattern = Regex("""(\d+)\s*(?:h|hours?|Stunden?|heures?)\s*(?:(\d+)\s*(?:min|minutes?|Minuten?))?""", RegexOption.IGNORE_CASE)
        hoursMinPattern.find(text)?.let { match ->
            val hours = match.groupValues[1].toIntOrNull() ?: 0
            val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0

            totalMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L)

            Log.d(TAG, "Parsed hours/min duration '$text': ${hours}h ${minutes}min = ${totalMs}ms")
            return totalMs
        }

        // Pattern 4: Just minutes "58 min" or "58 minutes"
        val minOnlyPattern = Regex("""(\d+)\s*(?:min|minutes?|Minuten?)""", RegexOption.IGNORE_CASE)
        minOnlyPattern.find(text)?.let { match ->
            val minutes = match.groupValues[1].toIntOrNull() ?: 0
            totalMs = minutes * 60 * 1000L
            Log.d(TAG, "Parsed minutes-only duration '$text': ${minutes}min = ${totalMs}ms")
            return totalMs
        }

        Log.w(TAG, "Could not parse duration: '$text'")
        return null
    }

    /**
     * Data class for sensor information
     */
    data class SensorInfo(
        val serialNumber: String?,
        val sensorStartTime: Long,  // Timestamp when sensor was inserted
        val sensorEndTime: Long?,   // Optional: when sensor session ends
        val durationText: String    // Original duration text (for logging)
    )

    /**
     * Data class for insulin/reservoir information (IAGE)
     */
    data class InsulinInfo(
        val fillTime: Long,         // Timestamp when reservoir was filled
        val durationText: String    // Original duration text (for logging)
    )

    /**
     * Combined data class for both SAGE and IAGE
     */
    data class AgeInfo(
        val sensorInfo: SensorInfo?,
        val insulinInfo: InsulinInfo?
    )

    /**
     * Extract sensor (SAGE) and insulin (IAGE) information from CamAPS FX burger menu.
     * Supports multiple languages:
     * - DE: "Anlage seit", "Füllung seit", "Ende Sensorsitzung"
     * - EN: "Inserted since", "Filled since", "Sensor session end"
     * - FR: "Insertion depuis", "Remplissage depuis", "Fin session capteur"
     *
     * @param rootNode The root accessibility node
     * @param service The accessibility service for getting updated windows
     * @return AgeInfo containing both SAGE and IAGE if found
     */
    suspend fun extractAgeInfo(
        rootNode: AccessibilityNodeInfo,
        service: android.accessibilityservice.AccessibilityService
    ): AgeInfo? {
        try {
            Log.d(TAG, "=== Starting SAGE/IAGE Extraction ===")

            // Step 1: Find and click burger menu button
            val menuButton = findBurgerMenuButton(rootNode)
            if (menuButton == null) {
                Log.w(TAG, "Could not find burger menu button")
                return null
            }

            Log.d(TAG, "Found burger menu button, clicking...")
            menuButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            menuButton.recycle()

            // Wait for menu to open
            delay(1000)

            // Step 2: Get the menu content
            val menuNode = service.rootInActiveWindow
            if (menuNode == null) {
                Log.w(TAG, "Could not get menu window")
                return null
            }

            // Step 3: Collect all text from menu
            val allText = mutableListOf<String>()
            collectAllText(menuNode, allText)

            Log.d(TAG, "=== Burger Menu Text (${allText.size} items) ===")
            allText.forEachIndexed { index, text ->
                Log.d(TAG, "  Menu[$index]: '$text'")
            }

            // Step 4: Look for sensor and insulin information
            var sensorInfo: SensorInfo? = null
            var insulinInfo: InsulinInfo? = null
            var serialNumber: String? = null

            // Multi-language patterns for SAGE (sensor age)
            val sageLabels = listOf(
                "Anlage seit",           // DE: Insertion since
                "Inserted since",        // EN
                "Insertion depuis",      // FR
                "Sensor since",          // EN alternative
                "Capteur depuis"         // FR alternative
            )

            // Multi-language patterns for IAGE (insulin/reservoir age)
            val iageLabels = listOf(
                "Füllung seit",          // DE: Filled since
                "Filled since",          // EN
                "Remplissage depuis",    // FR
                "Reservoir since",       // EN alternative
                "Réservoir depuis"       // FR alternative
            )

            // Multi-language patterns for sensor session end
            val sensorEndLabels = listOf(
                "Ende Sensorsitzung",    // DE
                "Sensor session end",    // EN
                "Fin session capteur"    // FR
            )

            // Parse menu items - label and value might be in separate items
            for (i in allText.indices) {
                val text = allText[i]
                val nextText = allText.getOrNull(i + 1) ?: ""

                // Extract sensor type (Companion CGM, Freestyle Libre, Dexcom, etc.)
                if (text.contains("Companion CGM", ignoreCase = true) ||
                    text.contains("Freestyle Libre", ignoreCase = true) ||
                    text.contains("Dexcom", ignoreCase = true) ||
                    text.contains("Libre", ignoreCase = true)) {
                    // Next item might be the name/serial
                    val sinceKeywords = listOf("seit", "since", "depuis")
                    if (nextText.isNotBlank() && sinceKeywords.none { nextText.contains(it, ignoreCase = true) }) {
                        serialNumber = nextText
                        Log.d(TAG, "Found sensor: '$text', name/serial: $serialNumber")
                    }
                }

                // SAGE: Look for sensor insertion labels
                if (sageLabels.any { text.equals(it, ignoreCase = true) }) {
                    val duration = parseDuration(nextText)
                    if (duration != null) {
                        val sensorStartTime = System.currentTimeMillis() - duration

                        Log.d(TAG, "Found SAGE label '$text': '$nextText' = ${duration}ms ago")
                        Log.d(TAG, "Calculated sensor start time: $sensorStartTime")

                        sensorInfo = SensorInfo(
                            serialNumber = serialNumber,
                            sensorStartTime = sensorStartTime,
                            sensorEndTime = null,
                            durationText = nextText
                        )
                    }
                }

                // SAGE: Look for sensor session end labels
                if (sensorEndLabels.any { text.equals(it, ignoreCase = true) }) {
                    val duration = parseDuration(nextText)
                    if (duration != null) {
                        val sensorEndTime = System.currentTimeMillis() + duration
                        Log.d(TAG, "Found sensor end label '$text': '$nextText' = in ${duration}ms")
                        sensorInfo = sensorInfo?.copy(sensorEndTime = sensorEndTime)
                    }
                }

                // IAGE: Look for insulin/reservoir fill labels
                if (iageLabels.any { text.equals(it, ignoreCase = true) }) {
                    val duration = parseDuration(nextText)
                    if (duration != null) {
                        val fillTime = System.currentTimeMillis() - duration

                        Log.d(TAG, "Found IAGE label '$text': '$nextText' = ${duration}ms ago")
                        Log.d(TAG, "Calculated insulin fill time: $fillTime")

                        insulinInfo = InsulinInfo(
                            fillTime = fillTime,
                            durationText = nextText
                        )
                    }
                }
            }

            // Step 5: Close the menu (press back or find close button)
            val closeButton = findCloseButton(menuNode) ?: findBackButton(menuNode)
            if (closeButton != null) {
                Log.d(TAG, "Closing menu...")
                closeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                closeButton.recycle()
            } else {
                Log.d(TAG, "No close button found, using GLOBAL_ACTION_BACK")
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }

            menuNode.recycle()

            // Log results
            if (sensorInfo != null) {
                Log.d(TAG, "✓ SAGE extracted: serial=${sensorInfo.serialNumber}, " +
                        "startTime=${sensorInfo.sensorStartTime}, duration=${sensorInfo.durationText}")
            } else {
                Log.w(TAG, "Could not find SAGE in menu")
            }

            if (insulinInfo != null) {
                Log.d(TAG, "✓ IAGE extracted: fillTime=${insulinInfo.fillTime}, " +
                        "duration=${insulinInfo.durationText}")
            } else {
                Log.w(TAG, "Could not find IAGE in menu")
            }

            return AgeInfo(sensorInfo, insulinInfo)

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting age info", e)
            return null
        }
    }

    /**
     * Legacy function for backwards compatibility
     */
    suspend fun extractSensorInfo(
        rootNode: AccessibilityNodeInfo,
        service: android.accessibilityservice.AccessibilityService
    ): SensorInfo? {
        return extractAgeInfo(rootNode, service)?.sensorInfo
    }

    /**
     * Find the burger menu button (hamburger icon, usually 3 horizontal lines)
     * Multi-language support: DE, EN, FR
     */
    private fun findBurgerMenuButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 15) return null

        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        // Log clickable nodes to help debug
        if (node.isClickable && depth < 5) {
            Log.d(TAG, "BurgerSearch[depth=$depth]: class=$className, viewId=$viewId, " +
                    "contentDesc='$contentDesc', text='$text'")
        }

        if (node.isClickable) {
            val matches = contentDesc.contains("menu") ||
                    contentDesc.contains("menü") ||              // DE
                    contentDesc.contains("navigation") ||
                    contentDesc.contains("hamburger") ||
                    contentDesc.contains("drawer") ||
                    contentDesc.contains("open drawer") ||       // EN
                    contentDesc.contains("ouvrir le tiroir") ||  // FR
                    contentDesc.contains("öffnen") ||            // DE
                    contentDesc.contains("open") ||              // EN
                    contentDesc.contains("ouvrir") ||            // FR
                    contentDesc.contains("offene optionen") ||   // DE: CamAPS FX specific
                    contentDesc.contains("open options") ||      // EN
                    contentDesc.contains("options ouvertes") ||  // FR
                    contentDesc.contains("optionen") ||          // DE
                    contentDesc.contains("options") ||           // EN/FR
                    text.contains("☰") ||
                    text.contains("≡") ||
                    viewId.contains("menu") ||
                    viewId.contains("burger") ||
                    viewId.contains("drawer") ||
                    viewId.contains("navigation")

            if (matches) {
                Log.d(TAG, "✓ Found burger menu button: $contentDesc / $text / $viewId")
                return node
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findBurgerMenuButton(child, depth + 1)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }

        return null
    }

    /**
     * Find a back button in the view hierarchy
     */
    private fun findBackButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 15) return null

        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (node.isClickable) {
            val matches = contentDesc.contains("back") ||
                    contentDesc.contains("zurück") ||
                    contentDesc.contains("navigate up") ||
                    text.contains("←") ||
                    text.contains("back") ||
                    viewId.contains("back") ||
                    viewId.contains("up")

            if (matches) {
                Log.d(TAG, "Found back button: $contentDesc / $text / $viewId")
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findBackButton(child, depth + 1)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }

        return null
    }
}
