package com.diabetesscreenreader.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseTrend
import com.diabetesscreenreader.data.GlucoseUnit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Specialized reader for mylife CamAPS FX app
 * Reads glucose data from main screen and information dialog
 */
class CamAPSFXReader {

    /**
     * Data class for treatment entries found via OCR in the graph
     */
    data class GraphTreatment(
        val insulinUnits: Double? = null,  // IE value (e.g., 7.5)
        val carbsGrams: Int? = null,       // g value (e.g., 30)
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val hasInsulin: Boolean get() = insulinUnits != null && insulinUnits > 0
        val hasCarbs: Boolean get() = carbsGrams != null && carbsGrams > 0
        val hasBoth: Boolean get() = hasInsulin && hasCarbs
    }

    companion object {
        private const val TAG = "CamAPSFXReader"
        private const val PACKAGE_NAME = "com.camdiab.fx.camaps"
        private const val PACKAGE_NAME_MGDL = "com.camdiab.fx_alert.mgdl"
        private const val PACKAGE_NAME_MMOL = "com.camdiab.fx_alert.mmol"
        private const val MAX_DEPTH = 20

        // Regex patterns for OCR treatment markers
        // Carbs pattern: matches "30 g" or "30g" but NOT "mg" or part of "Glukose"
        // Uses negative lookbehind for 'm' to avoid matching "mg"
        // Requires word boundary after 'g' to avoid matching "Glukose"
        private val CARBS_PATTERN = Regex("""(?<!m)(\d{1,3})\s*g\b""", RegexOption.IGNORE_CASE)
        // Time label pattern: matches "21:00", "23:00", "00:00"
        private val TIME_PATTERN = Regex("""(\d{2}):(\d{2})""")

        // Duplicate detection window: ±30 minutes
        private const val DUPLICATE_WINDOW_MS = 30 * 60 * 1000L  // 30 minutes

        // Check if package belongs to CamAPS FX (any variant)
        fun isCamAPSFXPackage(packageName: String?): Boolean {
            return packageName?.startsWith("com.camdiab.fx") == true
        }
    }

    // Track last sent meal for duplicate detection
    private var lastSentMealCarbs: Int? = null
    private var lastSentMealTime: Long = 0L

    // Callback for when OCR finds NEW treatments (after duplicate check)
    var onTreatmentFound: ((GraphTreatment) -> Unit)? = null

    /**
     * Check if a carbs value is a duplicate of the last sent meal
     * Returns true if this appears to be a duplicate (same carbs within ±30 min)
     */
    private fun isDuplicateMeal(carbsGrams: Int): Boolean {
        val lastCarbs = lastSentMealCarbs ?: return false
        val timeDiff = kotlin.math.abs(System.currentTimeMillis() - lastSentMealTime)

        // Consider it a duplicate if same carbs amount within the time window
        val isDuplicate = (carbsGrams == lastCarbs) && (timeDiff < DUPLICATE_WINDOW_MS)

        if (isDuplicate) {
            Log.d(TAG, "Duplicate meal detected: $carbsGrams g (last: $lastCarbs g, ${timeDiff / 60000}min ago)")
        }

        return isDuplicate
    }

    /**
     * Mark a meal as sent (for duplicate tracking)
     */
    private fun markMealAsSent(carbsGrams: Int) {
        lastSentMealCarbs = carbsGrams
        lastSentMealTime = System.currentTimeMillis()
        Log.d(TAG, "Marked meal as sent: $carbsGrams g at ${lastSentMealTime}")
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

                    // Close dialog by clicking X button
                    val closeButton = findCloseButton(dialogNode)
                    if (closeButton != null) {
                        Log.d(TAG, "Closing info dialog...")
                        closeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        Log.w(TAG, "Could not find close button")
                    }

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
                }
            } else {
                if (infoButton == null) {
                    Log.w(TAG, "Could not find info button, returning main screen data only")
                } else {
                    Log.w(TAG, "Service not provided, cannot read dialog")
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
                return found
            }
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
                    contentDesc.contains("geschlossene optionen") ||  // DE: CamAPS FX drawer toggle (when open)
                    contentDesc.contains("closed options") ||         // EN: drawer toggle
                    contentDesc.contains("options fermées") ||        // FR: drawer toggle
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
                return found
            }
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
     * EXPLORATORY: Opens the landscape/graph view and logs all content.
     * This is used to understand what data is available on the landscape screen.
     *
     * The button is identified by:
     * - DE: "Bildschirm drehen"
     * - EN: "Rotate screen"
     * - FR: "Rotation écran"
     */
    suspend fun exploreLandscapeView(
        rootNode: AccessibilityNodeInfo,
        service: android.accessibilityservice.AccessibilityService
    ): Boolean {
        try {
            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║     DEBUG: LANDSCAPE VIEW EXPLORATION                      ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

            // Step 1: Find the rotate/landscape button
            val rotateButton = findRotateScreenButton(rootNode)
            if (rotateButton == null) {
                Log.w(TAG, "Could not find rotate screen button")
                return false
            }

            val buttonId = rotateButton.viewIdResourceName ?: "unknown"
            Log.d(TAG, "✓ Found rotate screen button (id=$buttonId), clicking NOW...")
            val clickResult = rotateButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Click result: $clickResult")

            // Step 2: Wait for view to load, then dump EVERYTHING
            Log.d(TAG, "Waiting 3 seconds for landscape view to fully load...")
            delay(3000)

            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║     FULL SCREEN DUMP - ALL ACCESSIBLE ELEMENTS             ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")

            val currentNode = service.rootInActiveWindow
            if (currentNode == null) {
                Log.w(TAG, "No window available!")
                return false
            }

            val packageName = currentNode.packageName?.toString() ?: "unknown"
            Log.d(TAG, "")
            Log.d(TAG, "=== WINDOW INFO ===")
            Log.d(TAG, "Package: $packageName")
            Log.d(TAG, "Class: ${currentNode.className}")
            Log.d(TAG, "ChildCount: ${currentNode.childCount}")

            // Dump ALL nodes with full details
            Log.d(TAG, "")
            Log.d(TAG, "=== ALL NODES (FULL TREE) ===")
            dumpFullNodeTree(currentNode, 0)

            // Collect and log all text
            Log.d(TAG, "")
            Log.d(TAG, "=== ALL TEXT ON SCREEN ===")
            val allText = mutableListOf<String>()
            collectAllText(currentNode, allText)
            allText.forEachIndexed { index, text ->
                Log.d(TAG, "  Text[$index]: '$text'")
            }

            // Log all clickable elements
            Log.d(TAG, "")
            Log.d(TAG, "=== ALL CLICKABLE ELEMENTS ===")
            logAllClickableElements(currentNode)

            // Step 3: Take a screenshot of the landscape view for OCR analysis
            // Wait a bit more to ensure the landscape view is fully rendered
            Log.d(TAG, "Waiting 1 second for landscape view to stabilize...")
            delay(1000)

            Log.d(TAG, "")
            Log.d(TAG, "=== TAKING SCREENSHOT FOR OCR ===")
            takeScreenshotForOCR(service)

            Log.d(TAG, "")
            Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║     DEBUG COMPLETE - Landscape will close after OCR        ║")
            Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")
            Log.d(TAG, "")

            // Landscape view will be closed automatically after OCR completes
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error exploring landscape view", e)
            return false
        }
    }

    /**
     * Dumps the full node tree with all details for debugging
     */
    private fun dumpFullNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 15) return // Limit depth to avoid too much output

        val indent = "  ".repeat(depth)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val info = buildString {
            append("$indent[$depth] $className")
            if (viewId.isNotEmpty()) append(" id=$viewId")
            if (text.isNotEmpty()) append(" text='${text.take(50)}'")
            if (desc.isNotEmpty()) append(" desc='${desc.take(50)}'")
            if (node.isClickable) append(" [CLICKABLE]")
            if (node.isScrollable) append(" [SCROLLABLE]")
            append(" bounds=${bounds.width()}x${bounds.height()}")
        }
        Log.d(TAG, info)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpFullNodeTree(child, depth + 1)
        }
    }

    /**
     * Takes a screenshot of the current screen and runs OCR to find treatments
     */
    private fun takeScreenshotForOCR(service: android.accessibilityservice.AccessibilityService) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Log.d(TAG, "Taking screenshot for OCR (API 30+)...")

                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            Log.d(TAG, "Screenshot successful!")
                            val hardwareBitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            if (hardwareBitmap != null) {
                                // Convert hardware bitmap to software bitmap for ML Kit
                                val bitmap = hardwareBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                hardwareBitmap.recycle()
                                screenshot.hardwareBuffer.close()

                                // Run OCR on the bitmap
                                runOCR(bitmap, service)
                            } else {
                                Log.e(TAG, "Failed to create bitmap from screenshot")
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        }
                    }
                )
            } else {
                Log.w(TAG, "Screenshot requires API 30+, current: ${android.os.Build.VERSION.SDK_INT}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
        }
    }

    /**
     * Runs ML Kit OCR on the bitmap and parses results for treatments
     */
    private fun runOCR(bitmap: android.graphics.Bitmap, service: android.accessibilityservice.AccessibilityService) {
        try {
            Log.d(TAG, "=== RUNNING ML KIT OCR ===")
            Log.d(TAG, "Bitmap size: ${bitmap.width}x${bitmap.height}")

            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    Log.d(TAG, "OCR completed successfully!")

                    // Log each text block with bounding box
                    Log.d(TAG, "=== TEXT BLOCKS WITH BOUNDING BOXES ===")
                    visionText.textBlocks.forEachIndexed { blockIndex, block ->
                        val box = block.boundingBox
                        val boxStr = if (box != null) "[${box.left},${box.top} - ${box.right},${box.bottom}]" else "[no box]"
                        Log.d(TAG, "Block[$blockIndex] $boxStr: '${block.text.replace("\n", "\\n")}'")

                        // Also log individual lines within each block
                        block.lines.forEachIndexed { lineIndex, line ->
                            val lineBox = line.boundingBox
                            val lineBoxStr = if (lineBox != null) "[${lineBox.left},${lineBox.top} - ${lineBox.right},${lineBox.bottom}]" else "[no box]"
                            Log.d(TAG, "  Line[$lineIndex] $lineBoxStr: '${line.text}'")
                        }
                    }
                    Log.d(TAG, "=== END TEXT BLOCKS ===")

                    Log.d(TAG, "Full text found:\n${visionText.text}")

                    // Parse the recognized text for treatments with time interpolation
                    val treatments = parseOCRResultsWithTimestamps(visionText)

                    Log.d(TAG, "")
                    Log.d(TAG, "=== OCR RESULTS ===")
                    Log.d(TAG, "Found ${treatments.size} treatment(s):")
                    treatments.forEachIndexed { index, treatment ->
                        Log.d(TAG, "  Treatment[$index]: insulin=${treatment.insulinUnits} IE, carbs=${treatment.carbsGrams} g")
                        Log.d(TAG, "    hasInsulin=${treatment.hasInsulin}, hasCarbs=${treatment.hasCarbs}, hasBoth=${treatment.hasBoth}")
                    }

                    // Process carbs treatments (we only care about carbs for meal entries)
                    // Take the LAST carbs value found (most recent visible on graph)
                    val lastCarbsTreatment = treatments.lastOrNull { it.hasCarbs }

                    if (lastCarbsTreatment != null && lastCarbsTreatment.carbsGrams != null) {
                        val carbsGrams = lastCarbsTreatment.carbsGrams
                        if (!isDuplicateMeal(carbsGrams)) {
                            Log.d(TAG, "  → NEW meal found: $carbsGrams g - notifying callback")
                            markMealAsSent(carbsGrams)
                            onTreatmentFound?.invoke(lastCarbsTreatment)
                        } else {
                            Log.d(TAG, "  → Skipping duplicate meal: $carbsGrams g")
                        }
                    } else {
                        Log.d(TAG, "  → No carbs treatments to process")
                    }

                    // Also save screenshot for debugging
                    saveScreenshotForDebug(bitmap, service, treatments)
                    bitmap.recycle()

                    // Close landscape view after OCR
                    closeLandscapeView(service)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    bitmap.recycle()

                    // Still close landscape view even on failure
                    closeLandscapeView(service)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error running OCR", e)
            bitmap.recycle()
        }
    }

    /**
     * Closes the landscape view by clicking the "Bildschirm drehen" button again
     * or by performing a back action
     */
    private fun closeLandscapeView(service: android.accessibilityservice.AccessibilityService) {
        try {
            Log.d(TAG, "Closing landscape view...")

            // Small delay to ensure OCR result logging is visible
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Try to find and click the rotate button to close landscape
                val windows = service.windows
                for (window in windows) {
                    val rootNode = window.root ?: continue
                    val packageName = rootNode.packageName?.toString() ?: ""

                    if (isCamAPSFXPackage(packageName)) {
                        // Look for the rotate button in landscape view
                        val rotateButton = findRotateScreenButton(rootNode)
                        if (rotateButton != null) {
                            Log.d(TAG, "Found rotate button in landscape, clicking to close...")
                            rotateButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Landscape view closed successfully")
                            return@postDelayed
                        }
                    }
                }

                // Fallback: perform BACK action
                Log.d(TAG, "Rotate button not found, performing BACK action")
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

            }, 500) // 500ms delay before closing

        } catch (e: Exception) {
            Log.e(TAG, "Error closing landscape view", e)
        }
    }

    /**
     * Data class for time label with X position
     */
    private data class TimeLabel(
        val hour: Int,
        val minute: Int,
        val xCenter: Int
    )

    /**
     * Parses OCR text blocks to find carbs (g) values with their estimated timestamps
     * Uses the X position of time labels to interpolate when each carbs entry occurred
     */
    private fun parseOCRResultsWithTimestamps(visionText: com.google.mlkit.vision.text.Text): List<GraphTreatment> {
        val treatments = mutableListOf<GraphTreatment>()
        val timeLabels = mutableListOf<TimeLabel>()
        val carbsWithPosition = mutableListOf<Triple<Int, Int, String>>() // carbs, xCenter, originalText

        Log.d(TAG, "=== PARSING OCR WITH TIME INTERPOLATION ===")

        // Collect all potential time labels first (to find their Y range)
        data class PotentialTimeLabel(val hour: Int, val minute: Int, val xCenter: Int, val yTop: Int)
        val potentialTimeLabels = mutableListOf<PotentialTimeLabel>()

        // First pass: collect all HH:MM patterns with their positions
        for (block in visionText.textBlocks) {
            val box = block.boundingBox ?: continue
            val text = block.text.trim()
            val xCenter = (box.left + box.right) / 2

            val timeMatch = TIME_PATTERN.find(text)
            if (timeMatch != null) {
                val hour = timeMatch.groupValues[1].toIntOrNull() ?: continue
                val minute = timeMatch.groupValues[2].toIntOrNull() ?: continue
                potentialTimeLabels.add(PotentialTimeLabel(hour, minute, xCenter, box.top))
            }
        }

        // Find the most common Y level for time labels (X-axis labels should be at similar Y)
        // Group by Y position (within 100px tolerance)
        val yGroups = potentialTimeLabels.groupBy { (it.yTop / 100) * 100 }
        val largestYGroup = yGroups.maxByOrNull { it.value.size }?.value ?: emptyList()
        val timeLabelsYThreshold = if (largestYGroup.isNotEmpty()) {
            val avgY = largestYGroup.map { it.yTop }.average().toInt()
            Log.d(TAG, "Detected time labels Y range around Y=$avgY (${largestYGroup.size} labels)")
            avgY - 100 // Allow some tolerance above
        } else {
            Log.d(TAG, "No time label Y range detected, using default")
            500 // Default threshold
        }

        // Second pass: extract time labels and carbs markers
        for (block in visionText.textBlocks) {
            val box = block.boundingBox ?: continue
            val text = block.text.trim()
            val xCenter = (box.left + box.right) / 2

            // Check if this is a time label (at the detected Y level for X-axis)
            val timeMatch = TIME_PATTERN.find(text)
            if (timeMatch != null && box.top >= timeLabelsYThreshold) {
                val hour = timeMatch.groupValues[1].toIntOrNull() ?: continue
                val minute = timeMatch.groupValues[2].toIntOrNull() ?: continue
                timeLabels.add(TimeLabel(hour, minute, xCenter))
                Log.d(TAG, "  Time label found: ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')} at X=$xCenter, Y=${box.top}")
            }

            // Check if this is a carbs marker (above the time labels)
            val carbsMatch = CARBS_PATTERN.find(text)
            if (carbsMatch != null && box.top < timeLabelsYThreshold) {
                val carbsValue = carbsMatch.groupValues[1].toIntOrNull()
                if (carbsValue != null && carbsValue in 1..200) {
                    carbsWithPosition.add(Triple(carbsValue, xCenter, text))
                    Log.d(TAG, "  Carbs marker found: $carbsValue g at X=$xCenter, Y=${box.top} (text: '$text')")
                }
            }
        }

        // Sort time labels by X position (left to right)
        timeLabels.sortBy { it.xCenter }
        Log.d(TAG, "Sorted time labels: ${timeLabels.map { "${it.hour}:${it.minute.toString().padStart(2, '0')}@X${it.xCenter}" }}")

        if (timeLabels.size < 2) {
            Log.d(TAG, "Not enough time labels for interpolation (${timeLabels.size}), skipping carbs")
            // No fallback - we only upload meals with accurate timestamps
            return treatments
        }

        // For each carbs marker, interpolate the time
        for ((carbs, carbsX, origText) in carbsWithPosition) {
            val estimatedTime = interpolateTime(carbsX, timeLabels)
            if (estimatedTime != null) {
                treatments.add(GraphTreatment(carbsGrams = carbs, timestamp = estimatedTime))
                val dateStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(estimatedTime))
                Log.d(TAG, "  → Carbs $carbs g at X=$carbsX → estimated time: $dateStr")
            } else {
                Log.d(TAG, "  → Carbs $carbs g: could not interpolate time, skipping")
                // No fallback - skip this carbs entry
            }
        }

        return treatments
    }

    /**
     * Interpolates the timestamp based on X position between time labels
     */
    private fun interpolateTime(xPos: Int, timeLabels: List<TimeLabel>): Long? {
        if (timeLabels.size < 2) return null

        // Find the two time labels that bracket this X position
        var leftLabel: TimeLabel? = null
        var rightLabel: TimeLabel? = null

        for (i in 0 until timeLabels.size - 1) {
            if (timeLabels[i].xCenter <= xPos && timeLabels[i + 1].xCenter >= xPos) {
                leftLabel = timeLabels[i]
                rightLabel = timeLabels[i + 1]
                break
            }
        }

        // If outside range, extrapolate from nearest pair
        if (leftLabel == null || rightLabel == null) {
            if (xPos < timeLabels.first().xCenter) {
                leftLabel = timeLabels[0]
                rightLabel = timeLabels[1]
            } else {
                leftLabel = timeLabels[timeLabels.size - 2]
                rightLabel = timeLabels[timeLabels.size - 1]
            }
        }

        // Calculate the fraction between the two labels
        val xRange = rightLabel.xCenter - leftLabel.xCenter
        if (xRange <= 0) return null

        val fraction = (xPos - leftLabel.xCenter).toDouble() / xRange

        // Convert time labels to minutes since midnight, handling day boundary
        var leftMinutes = leftLabel.hour * 60 + leftLabel.minute
        var rightMinutes = rightLabel.hour * 60 + rightLabel.minute

        // Handle day boundary (e.g., 23:00 to 00:00)
        if (rightMinutes < leftMinutes) {
            rightMinutes += 24 * 60 // Add a day
        }

        val interpolatedMinutes = leftMinutes + (rightMinutes - leftMinutes) * fraction
        val finalMinutes = interpolatedMinutes % (24 * 60)

        val estimatedHour = (finalMinutes / 60).toInt()
        val estimatedMinute = (finalMinutes % 60).toInt()

        // Build timestamp for today (or yesterday if the time is in the future)
        val now = java.util.Calendar.getInstance()
        val estimated = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, estimatedHour)
            set(java.util.Calendar.MINUTE, estimatedMinute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        // If estimated time is in the future by more than 10 minutes, assume it was yesterday
        if (estimated.timeInMillis > now.timeInMillis + 10 * 60 * 1000) {
            estimated.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }

        Log.d(TAG, "    Interpolation: X=$xPos between ${leftLabel.hour}:${leftLabel.minute.toString().padStart(2,'0')}@${leftLabel.xCenter} " +
                "and ${rightLabel.hour}:${rightLabel.minute.toString().padStart(2,'0')}@${rightLabel.xCenter} " +
                "→ fraction=${"%.2f".format(fraction)} → $estimatedHour:${estimatedMinute.toString().padStart(2,'0')}")

        return estimated.timeInMillis
    }

    /**
     * Saves the screenshot for debugging with timestamp
     */
    private fun saveScreenshotForDebug(
        bitmap: android.graphics.Bitmap,
        service: android.accessibilityservice.AccessibilityService,
        treatments: List<GraphTreatment>
    ) {
        try {
            val dir = service.getExternalFilesDir(null)
            if (dir != null) {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val treatmentInfo = treatments.firstOrNull()?.let {
                    "_${it.insulinUnits ?: 0}IE_${it.carbsGrams ?: 0}g"
                } ?: "_no_treatment"
                val file = java.io.File(dir, "ocr_screenshot_$timestamp$treatmentInfo.png")

                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }

                Log.d(TAG, "✓ Debug screenshot saved: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving debug screenshot", e)
        }
    }

    /**
     * Data class to track rotate button candidates with their depth
     */
    private data class RotateButtonCandidate(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val viewId: String?
    )

    /**
     * Finds the "Rotate screen" / "Bildschirm drehen" button on the main screen.
     * Multi-language support: DE, EN, FR
     * Returns the button at the DEEPEST level (most likely to be on the topmost overlay)
     */
    private fun findRotateScreenButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        val candidates = mutableListOf<RotateButtonCandidate>()
        collectRotateButtons(node, depth, candidates)

        if (candidates.isEmpty()) {
            return null
        }

        // Log all candidates
        Log.d(TAG, "Found ${candidates.size} rotate button candidate(s):")
        candidates.forEach { candidate ->
            Log.d(TAG, "  - depth=${candidate.depth}, viewId=${candidate.viewId}")
        }

        // Return the one at the deepest depth (most likely on topmost view)
        val deepest = candidates.maxByOrNull { it.depth }
        if (deepest != null) {
            Log.d(TAG, "Selected deepest button at depth=${deepest.depth}, viewId=${deepest.viewId}")
            return deepest.node
        }

        return null
    }

    /**
     * Collects all rotate button candidates from the node tree
     */
    private fun collectRotateButtons(node: AccessibilityNodeInfo, depth: Int, candidates: MutableList<RotateButtonCandidate>) {
        if (depth > MAX_DEPTH) return

        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName

        // Multi-language support for rotate screen button
        if (node.isClickable) {
            val matches = contentDesc.contains("bildschirm drehen") ||  // DE
                    contentDesc.contains("rotate screen") ||             // EN
                    contentDesc.contains("rotation écran") ||            // FR
                    contentDesc.contains("rotate") ||
                    contentDesc.contains("landscape") ||
                    text.contains("bildschirm drehen") ||
                    text.contains("rotate screen")

            if (matches) {
                candidates.add(RotateButtonCandidate(node, depth, viewId))
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectRotateButtons(child, depth + 1, candidates)
            // Don't recycle child here - it might be added to candidates
        }
    }

    /**
     * Logs all clickable elements in a node tree for exploration.
     */
    private fun logAllClickableElements(node: AccessibilityNodeInfo, depth: Int = 0) {
        if (depth > MAX_DEPTH) return

        val contentDesc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (node.isClickable) {
            Log.d(TAG, "  Clickable[$depth]: class=$className, viewId=$viewId, desc='$contentDesc', text='$text'")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logAllClickableElements(child, depth + 1)
        }
    }

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
            } else {
                Log.d(TAG, "No close button found, using GLOBAL_ACTION_BACK")
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }

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
                return found
            }
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
                return found
            }
        }

        return null
    }
}
