package com.diabetesscreenreader.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.diabetesscreenreader.DiabetesScreenReaderApp
import com.diabetesscreenreader.R
import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseRepository
import com.diabetesscreenreader.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.min

class DiabetesAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DiabetesAccessibility"
        private const val MAX_DEPTH = 20
        const val ACTION_GLUCOSE_UPDATE = "com.diabetesscreenreader.GLUCOSE_UPDATE"
        const val ACTION_PERIODIC_READING = "com.diabetesscreenreader.PERIODIC_READING"

        // Exponential backoff settings
        private const val BASE_RETRY_DELAY_MS = 60_000L      // 1 minute
        private const val MAX_RETRY_DELAY_MS = 900_000L     // 15 minutes max

        @Volatile
        var instance: DiabetesAccessibilityService? = null
            private set

        fun isServiceRunning(context: android.content.Context? = null): Boolean {
            // Check if instance is available
            if (instance != null) return true

            // Fallback: check system settings
            val ctx = context ?: instance?.applicationContext ?: return false

            return try {
                val enabledServices = android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)

                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (componentName.equals(
                        "${ctx.packageName}/${DiabetesAccessibilityService::class.java.name}",
                        ignoreCase = true
                    )) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking service status", e)
                false
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastReadingTime = 0L
    private var lastReadingValue: Double? = null
    private val camapsFXReader = CamAPSFXReader()

    // AlarmManager for periodic reading (replaces Handler)
    private var alarmManager: AlarmManager? = null
    private var alarmPendingIntent: PendingIntent? = null
    private var alarmReceiver: BroadcastReceiver? = null

    // Exponential backoff for error handling
    private var consecutiveErrors = 0

    // Track if we woke the screen (so we can lock it again after reading)
    private var didWakeScreen = false

    // Track last saved bolus to prevent duplicates (amount, approximate timestamp)
    private var lastSavedBolus: Pair<Double, Long>? = null

    // Track when we last checked SAGE (Sensor Age)
    private var lastSageCheckTime = 0L

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

        // Initialize AlarmManager for periodic reading
        setupAlarmManager()
        scheduleNextReading(5000L) // First reading after 5 seconds
    }

    /**
     * Sets up AlarmManager and BroadcastReceiver for periodic reading.
     * This replaces the old Handler-based approach which was prone to busy-loops.
     */
    private fun setupAlarmManager() {
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create BroadcastReceiver for alarm
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_PERIODIC_READING) {
                    Log.d(TAG, "Alarm triggered - performing reading")
                    serviceScope.launch {
                        performPeriodicReading()
                    }
                }
            }
        }

        // Register receiver
        val filter = IntentFilter(ACTION_PERIODIC_READING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmReceiver, filter)
        }

        // Create PendingIntent for alarm
        val intent = Intent(ACTION_PERIODIC_READING).setPackage(packageName)
        alarmPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Log.d(TAG, "AlarmManager setup complete")
    }

    /**
     * Schedules the next reading using AlarmManager.
     * Uses setExactAndAllowWhileIdle for reliable timing even in Doze mode.
     */
    private fun scheduleNextReading(delayMs: Long) {
        val triggerTime = SystemClock.elapsedRealtime() + delayMs

        alarmManager?.let { am ->
            alarmPendingIntent?.let { pi ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi)
                }
                Log.d(TAG, "Next reading scheduled in ${delayMs / 1000}s")
            }
        }
    }

    /**
     * Cancels any pending alarm.
     */
    private fun cancelAlarm() {
        alarmPendingIntent?.let { pi ->
            alarmManager?.cancel(pi)
            Log.d(TAG, "Alarm cancelled")
        }
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
        if (event == null) {
            Log.d(TAG, "onAccessibilityEvent: event is null")
            return
        }

        // IMPORTANT: Copy event data BEFORE launching coroutine
        // The event object may be recycled by the system after this method returns
        val eventType = event.eventType
        val eventPackage = event.packageName?.toString() ?: ""

        serviceScope.launch {
            handleAccessibilityEvent(eventType, eventPackage)
        }
    }

    private suspend fun handleAccessibilityEvent(eventType: Int, eventPackage: String) {
        // Note: We only track events for debugging purposes
        // All actual reading happens via AlarmManager in performPeriodicReading()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Window changed to: $eventPackage")
        }
    }

    private fun deduplicateBolus(reading: GlucoseReading): GlucoseReading {
        // If no bolus data, nothing to deduplicate
        if (reading.bolusAmount == null || reading.bolusMinutesAgo == null) {
            return reading
        }

        // Calculate approximate timestamp when bolus was given
        val currentTime = System.currentTimeMillis()
        val bolusTimestamp = currentTime - (reading.bolusMinutesAgo * 60 * 1000L)

        // Check if this is the same bolus we already saved
        val isDuplicate = lastSavedBolus?.let { (savedAmount, savedTimestamp) ->
            // Same bolus if: same amount AND timestamp within 2 minutes
            savedAmount == reading.bolusAmount &&
            abs(savedTimestamp - bolusTimestamp) < 120_000L
        } ?: false

        return if (isDuplicate) {
            // Remove bolus data from this reading (already saved)
            Log.d(TAG, "Skipping duplicate bolus: ${reading.bolusAmount} IE (already saved)")
            reading.copy(bolusAmount = null, bolusMinutesAgo = null)
        } else {
            // New bolus - save it and track it
            Log.d(TAG, "New bolus detected: ${reading.bolusAmount} IE (${reading.bolusMinutesAgo} min ago)")
            lastSavedBolus = Pair(reading.bolusAmount, bolusTimestamp)
            reading
        }
    }

    private suspend fun performPeriodicReading() {
        val intervalMinutes = app.preferencesManager.readingIntervalMinutes.first()
        val normalDelayMs = intervalMinutes * 60 * 1000L

        try {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastReading = currentTime - lastReadingTime

            // Safety check: Don't read too frequently
            if (timeSinceLastReading < 30_000L) { // Minimum 30 seconds between reads
                Log.d(TAG, "Skipping reading - too soon (${timeSinceLastReading}ms)")
                scheduleNextReading(normalDelayMs)
                return
            }

            Log.d(TAG, "Starting periodic reading...")

            // Check if screen is locked
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val wasLocked = keyguardManager.isKeyguardLocked

            if (wasLocked) {
                Log.d(TAG, "Screen locked - waking up and unlocking...")
                didWakeScreen = true
                startLockscreenReading()

                // Wait for lockscreen to dismiss (max 10 seconds)
                var waitedMs = 0L
                while (keyguardManager.isKeyguardLocked && waitedMs < 10_000L) {
                    delay(500)
                    waitedMs += 500
                }

                if (keyguardManager.isKeyguardLocked) {
                    Log.w(TAG, "Screen still locked after 10s - skipping reading")
                    scheduleNextReading(normalDelayMs)
                    return
                }

                Log.d(TAG, "Screen unlocked after ${waitedMs}ms - now reading")
                delay(1000) // Extra wait for CamAPS FX to be ready
            }

            // Screen is now unlocked - perform reading
            Log.d(TAG, "Screen unlocked - reading...")
            performUnlockedReading()

            // If we woke the screen, lock it again after reading
            if (didWakeScreen) {
                Log.d(TAG, "Locking screen after reading...")
                delay(1000) // Brief delay before locking
                lockScreen()
                didWakeScreen = false
            }

            // Success - reset error counter and schedule next reading at normal interval
            consecutiveErrors = 0
            scheduleNextReading(normalDelayMs)

        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic reading", e)

            // Exponential backoff: 1min, 2min, 4min, 8min, 15min (max)
            consecutiveErrors++
            val backoffDelay = min(
                BASE_RETRY_DELAY_MS * (1 shl (consecutiveErrors - 1)),
                MAX_RETRY_DELAY_MS
            )
            Log.d(TAG, "Scheduling retry in ${backoffDelay / 1000}s (error #$consecutiveErrors)")
            scheduleNextReading(backoffDelay)
        }
    }

    /**
     * Starts the LockscreenReadingActivity to handle reading when screen is locked.
     */
    private fun startLockscreenReading() {
        val intent = Intent(this, LockscreenReadingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        startActivity(intent)
    }

    /**
     * Locks the screen using Accessibility Service global action.
     * Requires API 28+ (Android 9).
     */
    private fun lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.d(TAG, "Locking screen...")
            val success = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            if (success) {
                Log.d(TAG, "Screen locked successfully")
            } else {
                Log.w(TAG, "Failed to lock screen")
            }
        } else {
            Log.d(TAG, "Screen lock requires API 28+ (current: ${Build.VERSION.SDK_INT})")
        }
    }

    private fun createFullScreenIntentNotification(): Notification {
        val fullScreenIntent = Intent(this, LockscreenReadingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 1, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DiabetesScreenReaderApp.LOCKSCREEN_READING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Glukose-Abfrage läuft")
            .setContentText("Liest Daten von CamAPS FX...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    private fun dismissAnyOpenDialogs() {
        try {
            val windows = windows
            for (window in windows) {
                val rootNode = window.root ?: continue

                // Look for dialog with "Information" title or close buttons
                val closeButtons = mutableListOf<AccessibilityNodeInfo>()
                findCloseButtons(rootNode, closeButtons)

                var foundButton = false
                for (button in closeButtons) {
                    val contentDesc = button.contentDescription?.toString()?.lowercase() ?: ""
                    val text = button.text?.toString()?.lowercase() ?: ""

                    // Close button patterns
                    if (contentDesc.contains("quittieren") ||
                        contentDesc.contains("ablehnen") ||
                        contentDesc.contains("schließen") ||
                        contentDesc.contains("ok") ||
                        text.contains("ok") ||
                        text.contains("verstanden")) {

                        Log.d(TAG, "Found dialog close button: contentDesc='$contentDesc', text='$text'")
                        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        foundButton = true
                    }
                    button.recycle()
                }

                rootNode.recycle()

                if (foundButton) {
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing dialogs", e)
        }
    }

    private fun findCloseButtons(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable &&
            (node.className?.toString()?.contains("Button") == true ||
             node.className?.toString()?.contains("ImageButton") == true)) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findCloseButtons(child, result)
                // Only recycle if child was NOT added to result list
                if (!result.contains(child)) {
                    child.recycle()
                }
            }
        }
    }

    private suspend fun performUnlockedReading() {
        // Note: Lock check is done in performPeriodicReading()
        // This function is only called when screen is already unlocked
        Log.d(TAG, "Performing unlocked reading...")

        try {
            // Step 2: Get target package
            val targetPackage = app.preferencesManager.targetAppPackage.first()
            
            // Check if CamAPS FX is already visible
            val windows = windows
            var camapsFXWindow: AccessibilityNodeInfo? = null

            for (window in windows) {
                val rootNode = window.root
                if (rootNode != null) {
                    val packageName = rootNode.packageName?.toString() ?: ""
                    if (packageName == targetPackage ||
                        packageName.contains("camaps", ignoreCase = true) ||
                        packageName.contains("camdiab", ignoreCase = true)) {
                        camapsFXWindow = rootNode
                        Log.d(TAG, "Found CamAPS FX window: $packageName")
                        break
                    } else {
                        // Recycle nodes we don't need
                        rootNode.recycle()
                    }
                }
            }
            
            if (camapsFXWindow != null) {
                // CamAPS FX is visible - close any dialogs first, then read data
                dismissAnyOpenDialogs()
                delay(500) // Wait for dialog to close
                readDataAndNavigateBack(camapsFXWindow)
            } else {
                // CamAPS FX not visible - launch it and read
                Log.d(TAG, "CamAPS FX not visible - launching...")
                // Reset lastReadingTime so upcoming events can be read immediately
                lastReadingTime = 0L
                val launched = bringCamAPSFXToForeground(targetPackage)

                if (launched) {
                    // Wait for splash screen to finish and main screen to load
                    delay(8000)

                    // Try multiple times to find CamAPS FX (it might still be loading)
                    var foundCamAPS = false
                    for (attempt in 1..3) {
                        Log.d(TAG, "Looking for CamAPS FX (attempt $attempt/3)...")

                        // Use rootInActiveWindow - more reliable than iterating windows
                        val activeRoot = rootInActiveWindow
                        if (activeRoot != null) {
                            val packageName = activeRoot.packageName?.toString() ?: ""
                            Log.d(TAG, "Active window package: $packageName")

                            if (packageName == targetPackage ||
                                packageName.contains("camaps", ignoreCase = true) ||
                                packageName.contains("camdiab", ignoreCase = true)) {
                                Log.d(TAG, "Found CamAPS FX after launch!")
                                dismissAnyOpenDialogs()
                                delay(500)
                                readDataAndNavigateBack(activeRoot)
                                foundCamAPS = true
                                break
                            } else {
                                activeRoot.recycle()
                            }
                        }

                        if (!foundCamAPS && attempt < 3) {
                            Log.d(TAG, "CamAPS FX not active yet, waiting 2s...")
                            delay(2000)
                        }
                    }

                    if (!foundCamAPS) {
                        Log.w(TAG, "CamAPS FX not found after 3 attempts")
                    }
                } else {
                    Log.w(TAG, "Failed to launch CamAPS FX")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during reading", e)
        }
    }
    
    private suspend fun readDataAndNavigateBack(rootNode: AccessibilityNodeInfo) {
        try {
            // Extract data
            val glucoseData = camapsFXReader.extractData(rootNode, this)

            if (glucoseData != null) {
                // Validate data
                if (glucoseData.value <= 0) {
                    Log.e(TAG, "CRITICAL: Rejecting invalid value: ${glucoseData.value}")
                } else if (glucoseData.value < 40 || glucoseData.value > 400) {
                    Log.e(TAG, "CRITICAL: Rejecting out-of-range value: ${glucoseData.value}")
                } else {
                    // Save data
                    lastReadingTime = System.currentTimeMillis()
                    lastReadingValue = glucoseData.value

                    // Check if bolus is a duplicate and remove it if necessary
                    val finalData = deduplicateBolus(glucoseData)

                    Log.d(TAG, "Saving glucose reading: ${finalData.value} ${finalData.unit.getDisplayString()}")
                    repository.insertReading(finalData)

                    // Notify widget
                    sendBroadcast(Intent(ACTION_GLUCOSE_UPDATE))

                    // Check SAGE/IAGE periodically (interval from preferences)
                    val sageIntervalMinutes = app.preferencesManager.sageCheckIntervalMinutes.first()
                    val sageIntervalMs = sageIntervalMinutes * 60 * 1000L
                    val timeSinceLastSageCheck = System.currentTimeMillis() - lastSageCheckTime
                    if (timeSinceLastSageCheck >= sageIntervalMs) {
                        Log.d(TAG, "SAGE check due (${timeSinceLastSageCheck / 60000}min since last check, interval=${sageIntervalMinutes}min)")
                        performSAGECheck(rootNode)
                    }
                }
            } else {
                Log.w(TAG, "Failed to extract glucose data")
            }

            // No navigation back - leave CamAPS FX open (follower phone)
            Log.d(TAG, "Reading complete - leaving CamAPS FX open")

        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Checks sensor age (SAGE) and insulin age (IAGE) by reading from CamAPS FX burger menu
     * and comparing/updating Nightscout if needed.
     */
    private suspend fun performAgeCheck(rootNode: AccessibilityNodeInfo) {
        try {
            // Check if Nightscout is enabled
            val nightscoutEnabled = app.preferencesManager.nightscoutEnabled.first()
            if (!nightscoutEnabled) {
                Log.d(TAG, "Nightscout disabled - skipping SAGE/IAGE check")
                return
            }

            Log.d(TAG, "=== Starting SAGE/IAGE Check ===")

            // Need a fresh root node since we'll be navigating
            val freshRootNode = rootInActiveWindow
            if (freshRootNode == null) {
                Log.w(TAG, "Could not get fresh root node for age check")
                return
            }

            // Extract both sensor and insulin info from burger menu (single menu access)
            val ageInfo = camapsFXReader.extractAgeInfo(freshRootNode, this)

            if (ageInfo == null) {
                Log.w(TAG, "Could not extract age info from CamAPS FX menu")
                freshRootNode.recycle()
                return
            }

            // Check SAGE
            ageInfo.sensorInfo?.let { sensorInfo ->
                Log.d(TAG, "SAGE from app: serial=${sensorInfo.serialNumber}, " +
                        "startTime=${java.util.Date(sensorInfo.sensorStartTime)}, " +
                        "duration=${sensorInfo.durationText}")

                // Save to preferences for UI display
                app.preferencesManager.setSensorInfo(sensorInfo.sensorStartTime, sensorInfo.serialNumber)

                val result = app.nightscoutApi.checkAndUpdateSAGE(
                    appSensorStartTime = sensorInfo.sensorStartTime,
                    toleranceHours = 1.5,
                    sensorSerial = sensorInfo.serialNumber
                )

                result.onSuccess { status ->
                    Log.d(TAG, "SAGE check result: $status")
                }.onFailure { error ->
                    Log.e(TAG, "SAGE check failed: ${error.message}")
                }
            }

            // Check IAGE
            ageInfo.insulinInfo?.let { insulinInfo ->
                Log.d(TAG, "IAGE from app: fillTime=${java.util.Date(insulinInfo.fillTime)}, " +
                        "duration=${insulinInfo.durationText}")

                // Save to preferences for UI display
                app.preferencesManager.setInsulinFillTime(insulinInfo.fillTime)

                val result = app.nightscoutApi.checkAndUpdateIAGE(
                    appFillTime = insulinInfo.fillTime,
                    toleranceHours = 1.5
                )

                result.onSuccess { status ->
                    Log.d(TAG, "IAGE check result: $status")
                }.onFailure { error ->
                    Log.e(TAG, "IAGE check failed: ${error.message}")
                }
            }

            lastSageCheckTime = System.currentTimeMillis()
            freshRootNode.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "Error during age check", e)
        }
    }

    // Legacy alias for backwards compatibility
    private suspend fun performSAGECheck(rootNode: AccessibilityNodeInfo) = performAgeCheck(rootNode)

    private fun bringCamAPSFXToForeground(packageName: String): Boolean {
        return try {
            val intent = applicationContext.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                applicationContext.startActivity(intent)
                Log.d(TAG, "Launched app: $packageName")
                true
            } else {
                Log.w(TAG, "No launch intent found for: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
            false
        }
    }

    /**
     * Performs a swipe-up gesture to dismiss the lockscreen UI
     * (Only works when lockscreen security is already disabled)
     */
    private fun performSwipeUpGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Swipe gesture requires Android N or higher")
            return
        }

        Log.d(TAG, "Performing swipe-up gesture to dismiss lockscreen...")

        // Get display metrics to calculate swipe coordinates
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        // Swipe from bottom center to top center
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.9f // Start near bottom
        val endX = screenWidth / 2f
        val endY = screenHeight * 0.1f   // End near top

        val swipePath = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gestureDescription = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(swipePath, 0, 300))
            .build()

        val result = dispatchGesture(gestureDescription, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                Log.d(TAG, "Swipe gesture completed successfully")
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                Log.w(TAG, "Swipe gesture was cancelled")
            }
        }, null)

        if (!result) {
            Log.e(TAG, "Failed to dispatch swipe gesture")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel pending alarm
        cancelAlarm()

        // Unregister broadcast receiver
        alarmReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering alarm receiver", e)
            }
            alarmReceiver = null
        }

        // Cancel coroutine scope
        serviceScope.cancel()

        instance = null
        Log.d(TAG, "Accessibility Service destroyed")
    }

    fun refreshTargetPackage() {
        serviceScope.launch {
            updateServiceConfig()
        }
    }
}
