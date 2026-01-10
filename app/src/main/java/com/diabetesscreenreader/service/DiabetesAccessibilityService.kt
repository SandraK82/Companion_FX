package com.diabetesscreenreader.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.diabetesscreenreader.DiabetesScreenReaderApp
import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseRepository
import com.diabetesscreenreader.data.GlucoseTrend
import com.diabetesscreenreader.data.GlucoseUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DiabetesAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastReadingTime = 0L
    private var lastReadingValue: Double? = null
    private var isUserInTargetApp = false

    private val app: DiabetesScreenReaderApp
        get() = application as DiabetesScreenReaderApp

    private val repository: GlucoseRepository by lazy {
        GlucoseRepository(
            app.database.glucoseDao(),
            app.nightscoutApi,
            app.preferencesManager
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected")

        serviceScope.launch {
            updateServiceConfig()
        }

        instance = this
    }

    private suspend fun updateServiceConfig() {
        val targetPackage = app.preferencesManager.targetAppPackage.first()

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            packageNames = if (targetPackage.isNotBlank()) {
                arrayOf(targetPackage)
            } else {
                null // Listen to all apps if no target specified
            }

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        serviceScope.launch {
            handleAccessibilityEvent(event)
        }
    }

    private suspend fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val targetPackage = app.preferencesManager.targetAppPackage.first()

        // Track if user is currently in the target app
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isUserInTargetApp = event.packageName?.toString() == targetPackage
        }

        // Only read when user is NOT actively using the app (if setting enabled)
        val onlyReadWhenInactive = app.preferencesManager.onlyReadWhenAppInactive.first()
        if (onlyReadWhenInactive && isUserInTargetApp) {
            return
        }

        // Check reading interval
        val intervalMinutes = app.preferencesManager.readingIntervalMinutes.first()
        val minIntervalMs = intervalMinutes * 60 * 1000L
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastReadingTime < minIntervalMs) {
            return
        }

        // Try to extract glucose data
        val nodeInfo = event.source ?: rootInActiveWindow ?: return
        val glucoseData = extractGlucoseData(nodeInfo, event.packageName?.toString() ?: "")

        if (glucoseData != null) {
            // Avoid duplicate readings
            if (glucoseData.value == lastReadingValue &&
                currentTime - lastReadingTime < 60000) {
                return
            }

            lastReadingTime = currentTime
            lastReadingValue = glucoseData.value

            repository.insertReading(glucoseData)
            Log.d(TAG, "New glucose reading: ${glucoseData.value} ${glucoseData.unit.getDisplayString()}")

            // Notify widget to update
            sendBroadcast(Intent(ACTION_GLUCOSE_UPDATE))
        }

        nodeInfo.recycle()
    }

    private fun extractGlucoseData(nodeInfo: AccessibilityNodeInfo, packageName: String): GlucoseReading? {
        val extractedData = mutableMapOf<String, String>()

        // Traverse the accessibility tree
        traverseNode(nodeInfo, extractedData)

        // Try to parse glucose value
        val glucoseValue = parseGlucoseValue(extractedData)
        if (glucoseValue != null) {
            val trend = parseTrend(extractedData)
            val unit = parseUnit(extractedData)

            return GlucoseReading(
                value = glucoseValue,
                unit = unit,
                trend = trend,
                source = packageName,
                timestamp = System.currentTimeMillis()
            )
        }

        return null
    }

    private fun traverseNode(node: AccessibilityNodeInfo, data: MutableMap<String, String>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        // Store relevant text content
        if (text.isNotBlank()) {
            val key = if (viewId.isNotBlank()) viewId else "text_$depth"
            data[key] = text
        }

        if (contentDesc.isNotBlank()) {
            data["desc_$depth"] = contentDesc
        }

        // Check for glucose-related view IDs
        if (viewId.contains("glucose", ignoreCase = true) ||
            viewId.contains("bg", ignoreCase = true) ||
            viewId.contains("sgv", ignoreCase = true) ||
            viewId.contains("reading", ignoreCase = true)) {
            data["glucose_view"] = text.ifBlank { contentDesc }
        }

        // Recurse through children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, data, depth + 1)
            child.recycle()
        }
    }

    private fun parseGlucoseValue(data: Map<String, String>): Double? {
        // First check dedicated glucose view
        data["glucose_view"]?.let { value ->
            extractNumber(value)?.let { return it }
        }

        // Look for patterns in all text content
        for ((_, value) in data) {
            // Pattern: number followed by mg/dL or mmol/L
            val mgDlPattern = Regex("""(\d{2,3})\s*(mg/?dL|mg/dl)""", RegexOption.IGNORE_CASE)
            val mmolPattern = Regex("""(\d{1,2}[.,]\d)\s*(mmol/?L|mmol/l)""", RegexOption.IGNORE_CASE)

            mgDlPattern.find(value)?.let { match ->
                return match.groupValues[1].toDoubleOrNull()
            }

            mmolPattern.find(value)?.let { match ->
                return match.groupValues[1].replace(",", ".").toDoubleOrNull()
            }

            // Standalone numbers in glucose range (40-400 mg/dL)
            val standalone = Regex("""^\s*(\d{2,3})\s*$""")
            standalone.find(value)?.let { match ->
                val num = match.groupValues[1].toIntOrNull()
                if (num != null && num in 40..400) {
                    return num.toDouble()
                }
            }
        }

        return null
    }

    private fun parseTrend(data: Map<String, String>): GlucoseTrend {
        for ((_, value) in data) {
            // Check for trend arrows
            when {
                value.contains("↑↑") || value.contains("⇈") -> return GlucoseTrend.DOUBLE_UP
                value.contains("↑") || value.contains("⬆") -> return GlucoseTrend.SINGLE_UP
                value.contains("↗") || value.contains("⬈") -> return GlucoseTrend.FORTY_FIVE_UP
                value.contains("→") || value.contains("➡") -> return GlucoseTrend.FLAT
                value.contains("↘") || value.contains("⬊") -> return GlucoseTrend.FORTY_FIVE_DOWN
                value.contains("↓↓") || value.contains("⇊") -> return GlucoseTrend.DOUBLE_DOWN
                value.contains("↓") || value.contains("⬇") -> return GlucoseTrend.SINGLE_DOWN
            }

            // Check for text descriptions
            val lowerValue = value.lowercase()
            when {
                lowerValue.contains("rising fast") || lowerValue.contains("stark steigend") ->
                    return GlucoseTrend.DOUBLE_UP
                lowerValue.contains("rising") || lowerValue.contains("steigend") ->
                    return GlucoseTrend.SINGLE_UP
                lowerValue.contains("falling fast") || lowerValue.contains("stark fallend") ->
                    return GlucoseTrend.DOUBLE_DOWN
                lowerValue.contains("falling") || lowerValue.contains("fallend") ->
                    return GlucoseTrend.SINGLE_DOWN
                lowerValue.contains("stable") || lowerValue.contains("stabil") || lowerValue.contains("flat") ->
                    return GlucoseTrend.FLAT
            }
        }

        return GlucoseTrend.UNKNOWN
    }

    private fun parseUnit(data: Map<String, String>): GlucoseUnit {
        for ((_, value) in data) {
            if (value.contains("mmol", ignoreCase = true)) {
                return GlucoseUnit.MMOL_L
            }
            if (value.contains("mg/dL", ignoreCase = true) || value.contains("mg/dl")) {
                return GlucoseUnit.MG_DL
            }
        }
        // Default to mg/dL
        return GlucoseUnit.MG_DL
    }

    private fun extractNumber(text: String): Double? {
        val pattern = Regex("""(\d+[.,]?\d*)""")
        return pattern.find(text)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
        Log.d(TAG, "Accessibility Service destroyed")
    }

    fun refreshTargetPackage() {
        serviceScope.launch {
            updateServiceConfig()
        }
    }

    companion object {
        private const val TAG = "DiabetesAccessibility"
        private const val MAX_DEPTH = 20
        const val ACTION_GLUCOSE_UPDATE = "com.diabetesscreenreader.GLUCOSE_UPDATE"

        @Volatile
        var instance: DiabetesAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }
}
