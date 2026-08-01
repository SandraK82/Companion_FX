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
import android.os.BatteryManager
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
import com.diabetesscreenreader.data.GlucoseUnit
import com.diabetesscreenreader.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

        // Watchdog settings for hang detection
        private const val READING_WATCHDOG_TIMEOUT_MS = 45_000L  // 45 seconds max for entire reading
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L    // Check health every 30 seconds
        private const val STUCK_STATE_THRESHOLD_MS = 60_000L    // Consider stuck if screen on for 60s without completion

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
    @Volatile
    private var didWakeScreen = false

    // Watchdog state for hang detection
    @Volatile
    private var isReadingInProgress = false
    @Volatile
    private var readingStartTime = 0L
    private var healthCheckJob: Job? = null

    // Track when we last checked SAGE (Sensor Age)
    private var lastSageCheckTime = 0L

    // Flag to enable/disable landscape view exploration for OCR
    private val EXPLORE_LANDSCAPE_VIEW = true  // Set to false to disable

    private val app: DiabetesScreenReaderApp
        get() = application as DiabetesScreenReaderApp

    private val repository: GlucoseRepository by lazy {
        GlucoseRepository(
            app.database.glucoseDao(),
            app.database.companionEventDao(),
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

        // Set up OCR treatment callback.  OCR discoveries enter the same
        // durable ledger as dialog events; they are not uploaded directly.
        setupOCRTreatmentCallback()

        // Initialize AlarmManager for periodic reading
        setupAlarmManager()
        scheduleNextReading(5000L) // First reading after 5 seconds

        // Start health check watchdog
        startHealthCheckWatchdog()
    }

    /**
     * Sets up the callback for when OCR detects treatment markers in the
     * landscape graph.  The event is persisted first, then the normal retry
     * path uploads it with a stable syncIdentifier.
     */
    private fun setupOCRTreatmentCallback() {
        camapsFXReader.onTreatmentFound = { treatment ->
            serviceScope.launch {
                try {
                    repository.recordGraphTreatment(treatment)
                    val result = repository.flushPendingCompanionEvents()
                    result.onFailure { error ->
                        Log.w(TAG, "OCR treatment queued for retry: ${error.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error recording OCR treatment", e)
                }
            }
        }
        Log.d(TAG, "OCR treatment callback configured")
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    // Android 12+ may deny exact-alarm access even when the permission is
                    // declared. Fall back gracefully instead of crashing the accessibility
                    // service; exact-alarm access can still be granted for tighter timing.
                    am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pi
                    )
                    Log.w(TAG, "Exact alarm access unavailable; scheduled an inexact reading")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

    /**
     * Starts a periodic health check watchdog that monitors for stuck states.
     * This runs independently of the reading cycle to detect and recover from hangs.
     */
    private fun startHealthCheckWatchdog() {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                performHealthCheck()
            }
        }
        Log.d(TAG, "Health check watchdog started")
    }

    /**
     * Performs a health check to detect stuck states.
     * If a reading is in progress for too long, or the screen is stuck on, triggers recovery.
     */
    private suspend fun performHealthCheck() {
        val currentTime = System.currentTimeMillis()

        // Check if a reading is stuck (taking too long)
        if (isReadingInProgress && readingStartTime > 0) {
            val readingDuration = currentTime - readingStartTime
            if (readingDuration > STUCK_STATE_THRESHOLD_MS) {
                Log.w(TAG, "WATCHDOG: Reading stuck for ${readingDuration}ms - triggering recovery")
                performAutoRecovery("reading_timeout")
                return
            }
        }

        // Check if screen was woken but not locked back (stuck in half-awake state)
        if (didWakeScreen && !isReadingInProgress) {
            Log.w(TAG, "WATCHDOG: Screen woken but reading not in progress - possible stuck state")
            performAutoRecovery("stuck_screen")
            return
        }

        // Check if screen is on but shouldn't be (we woke it and reading finished long ago)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (powerManager.isInteractive && !keyguardManager.isKeyguardLocked && didWakeScreen) {
            // Screen is on and unlocked, but we're the ones who woke it
            // This might be a stuck state if no reading is happening. Do not
            // force-lock the phone: a secure keyguard would block the next
            // background cycle. Let Android apply the user's normal timeout.
            if (!isReadingInProgress) {
                Log.w(TAG, "WATCHDOG: Screen on and unlocked without active reading - clearing wake state")
                didWakeScreen = false
            }
        }
    }

    /**
     * Performs automatic recovery from a stuck state.
     * Resets all state and lets the normal Android screen timeout take over.
     */
    private fun performAutoRecovery(reason: String) {
        Log.w(TAG, "AUTO-RECOVERY triggered: $reason")

        try {
            // Reset all state flags
            isReadingInProgress = false
            readingStartTime = 0L

            // Clear the wake marker. Do not call GLOBAL_ACTION_LOCK_SCREEN:
            // with a PIN/password that would strand the next reading cycle at
            // the keyguard.
            if (didWakeScreen) {
                Log.d(TAG, "AUTO-RECOVERY: clearing wake state (no forced lock)")
                didWakeScreen = false
            }

            // Increment error counter for backoff
            consecutiveErrors++

            // Schedule next reading with backoff
            val backoffDelay = min(
                BASE_RETRY_DELAY_MS * (1 shl min(consecutiveErrors - 1, 4)),
                MAX_RETRY_DELAY_MS
            )
            Log.d(TAG, "AUTO-RECOVERY: Scheduling next reading in ${backoffDelay / 1000}s")
            scheduleNextReading(backoffDelay)

        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-recovery", e)
            // Even if recovery fails, try to schedule next reading
            scheduleNextReading(BASE_RETRY_DELAY_MS)
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

    private suspend fun performPeriodicReading() {
        val intervalMinutes = app.preferencesManager.readingIntervalMinutes.first()
        val normalDelayMs = intervalMinutes * 60 * 1000L

        // Mark reading as in progress for watchdog
        isReadingInProgress = true
        readingStartTime = System.currentTimeMillis()

        try {
            // Wrap entire reading in timeout to prevent indefinite hangs
            withTimeout(READING_WATCHDOG_TIMEOUT_MS) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastReading = currentTime - lastReadingTime

                // Safety check: Don't read too frequently
                if (timeSinceLastReading < 30_000L) { // Minimum 30 seconds between reads
                    Log.d(TAG, "Skipping reading - too soon (${timeSinceLastReading}ms)")
                    return@withTimeout
                }

                Log.d(TAG, "Starting periodic reading...")

                // The accessibility tree is not available while the display is
                // asleep, even when the phone has no secure keyguard. Wake the
                // screen for the short CamAPS read; the normal Android timeout
                // will turn it off again afterwards.
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val wasLocked = keyguardManager.isKeyguardLocked
                val wasScreenOff = !powerManager.isInteractive

                if (wasLocked || wasScreenOff) {
                    if (wasLocked) {
                        Log.d(TAG, "Screen locked - waking up and unlocking...")
                    } else {
                        Log.d(TAG, "Screen off - waking display for CamAPS read...")
                    }
                    didWakeScreen = true
                    startLockscreenReading()

                    // Wait for the display to wake and (if present) the
                    // lockscreen to dismiss (max 10 seconds).
                    var waitedMs = 0L
                    while ((keyguardManager.isKeyguardLocked || !powerManager.isInteractive) && waitedMs < 10_000L) {
                        delay(500)
                        waitedMs += 500
                    }

                    if (keyguardManager.isKeyguardLocked) {
                        Log.w(TAG, "Screen still locked after 10s - skipping reading")
                        return@withTimeout
                    }

                    if (!powerManager.isInteractive) {
                        Log.w(TAG, "Screen did not wake after 10s - skipping reading")
                        return@withTimeout
                    }

                    Log.d(TAG, "Display ready after ${waitedMs}ms - now reading")
                    delay(1000) // Extra wait for CamAPS FX to be ready
                }

                // Screen is now unlocked - perform reading
                Log.d(TAG, "Screen unlocked - reading...")
                performUnlockedReading()

                // Success - reset error counter
                consecutiveErrors = 0
            }

            // Schedule next reading at normal interval
            scheduleNextReading(normalDelayMs)

        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "TIMEOUT: Reading took longer than ${READING_WATCHDOG_TIMEOUT_MS}ms", e)
            consecutiveErrors++
            val backoffDelay = min(
                BASE_RETRY_DELAY_MS * (1 shl min(consecutiveErrors - 1, 4)),
                MAX_RETRY_DELAY_MS
            )
            Log.d(TAG, "Scheduling retry in ${backoffDelay / 1000}s (timeout error #$consecutiveErrors)")
            scheduleNextReading(backoffDelay)

        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic reading", e)

            // Exponential backoff: 1min, 2min, 4min, 8min, 15min (max)
            consecutiveErrors++
            val backoffDelay = min(
                BASE_RETRY_DELAY_MS * (1 shl min(consecutiveErrors - 1, 4)),
                MAX_RETRY_DELAY_MS
            )
            Log.d(TAG, "Scheduling retry in ${backoffDelay / 1000}s (error #$consecutiveErrors)")
            scheduleNextReading(backoffDelay)

        } finally {
            // CRITICAL: Always clean up state, even on error/timeout
            Log.d(TAG, "Cleaning up reading state...")

            // Never force-lock after a background read. A secure keyguard
            // would prevent the next cycle from reaching CamAPS. Android's
            // normal screen timeout still turns the display off, while a
            // lockscreen-free companion phone can be woken again later.
            if (didWakeScreen) {
                Log.d(TAG, "Reading cycle finished; leaving screen to normal timeout (no forced lock)")
                didWakeScreen = false
            }

            // Reset reading state
            isReadingInProgress = false
            readingStartTime = 0L
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
            .setContentTitle("Glucose reading in progress")
            .setContentText("Reading data from CamAPS FX...")
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
                }

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
        // Extract data
        val glucoseData = camapsFXReader.extractData(rootNode, this)

        if (glucoseData != null) {
            // Validate data
            val validRange = if (glucoseData.unit == GlucoseUnit.MMOL_L) {
                // Keep genuine low readings instead of using the normal target
                // range as a validity gate. The native CamAPS/xDrip alarms
                // remain authoritative for treatment decisions.
                0.1..40.0
            } else {
                40.0..400.0
            }
            if (glucoseData.value <= 0) {
                Log.e(TAG, "CRITICAL: Rejecting invalid value: ${glucoseData.value}")
            } else if (glucoseData.value !in validRange) {
                Log.e(TAG, "CRITICAL: Rejecting out-of-range value: ${glucoseData.value}")
            } else {
                // Save data
                lastReadingTime = System.currentTimeMillis()
                lastReadingValue = glucoseData.value

                // Persist treatment/device events before the network call.  A
                // repeated screen read resolves to the same fingerprint and
                // is ignored by Room, while a failed upload remains pending.
                val readingWithUploaderBattery = glucoseData.copy(
                    uploaderBattery = readUploaderBatteryPercent()
                )
                repository.recordEventsFromReading(readingWithUploaderBattery)

                Log.d(TAG, "Saving glucose reading: ${readingWithUploaderBattery.value} ${readingWithUploaderBattery.unit.getDisplayString()}")
                repository.insertReading(readingWithUploaderBattery)

                // Notify widget
                sendBroadcast(Intent(ACTION_GLUCOSE_UPDATE))

                // Check SAGE/IAGE periodically (interval from preferences)
                val sageIntervalMinutes = app.preferencesManager.sageCheckIntervalMinutes.first()
                val sageIntervalMs = sageIntervalMinutes * 60 * 1000L
                val timeSinceLastSageCheck = System.currentTimeMillis() - lastSageCheckTime
                if (timeSinceLastSageCheck >= sageIntervalMs) {
                    Log.d(TAG, "SAGE check due (${timeSinceLastSageCheck / 60000}min since last check, interval=${sageIntervalMinutes}min)")
                    performSAGECheck()
                }
            }
        } else {
            Log.w(TAG, "Failed to extract glucose data")
        }

        // No navigation back - leave CamAPS FX open (follower phone)
        Log.d(TAG, "Reading complete - leaving CamAPS FX open")

        // OCR: Explore landscape view to find carbs treatments in the graph
        if (EXPLORE_LANDSCAPE_VIEW) {
            Log.d(TAG, "=== Exploring landscape view for OCR ===")
            val freshRootNode = rootInActiveWindow
            if (freshRootNode != null) {
                val success = camapsFXReader.exploreLandscapeView(freshRootNode, this)
                if (success) {
                    Log.d(TAG, "Landscape view exploration completed successfully")
                }
            }
        }
    }

    private fun readUploaderBatteryPercent(): Int? {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            level.takeIf { it in 0..100 }
        } catch (e: Exception) {
            Log.w(TAG, "Companion battery is unavailable", e)
            null
        }
    }

    /**
     * Checks sensor age (SAGE) and insulin age (IAGE) by reading from CamAPS FX burger menu
     * and comparing/updating Nightscout if needed.
     */
    private suspend fun performAgeCheck() {
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
                return
            }

            // Check SAGE
            ageInfo.sensorInfo?.let { sensorInfo ->
                Log.d(TAG, "SAGE from CamAPS drawer: " +
                        "startTime=${java.util.Date(sensorInfo.sensorStartTime)}, " +
                        "duration=${sensorInfo.durationText}")

                // Save only the timestamp for local UI display. The visible
                // Companion CGM identifier is deliberately not retained.
                app.preferencesManager.setSensorInfo(sensorInfo.sensorStartTime, null)
                val observation = ageInfo.observation
                repository.recordSensorStart(
                    sensorStartTime = sensorInfo.sensorStartTime,
                    observedAt = observation?.observedAt ?: System.currentTimeMillis(),
                    source = observation?.source ?: "camaps_drawer_ocr",
                    confidence = observation?.confidence ?: 0.8
                )
            }

            // Check IAGE
            ageInfo.insulinInfo?.let { insulinInfo ->
                Log.d(TAG, "IAGE from app: fillTime=${java.util.Date(insulinInfo.fillTime)}, " +
                        "duration=${insulinInfo.durationText}")

                // Save to preferences for UI display
                app.preferencesManager.setInsulinFillTime(insulinInfo.fillTime)
                val observation = ageInfo.observation
                repository.recordInsulinChange(
                    fillTime = insulinInfo.fillTime,
                    observedAt = observation?.observedAt ?: System.currentTimeMillis(),
                    source = observation?.source ?: "camaps_drawer_ocr",
                    confidence = observation?.confidence ?: 0.8
                )
            }

            // Flush after both age events have been persisted.  The API uses
            // stable fingerprints, so this is safe across repeated checks.
            repository.flushPendingCompanionEvents()

            lastSageCheckTime = System.currentTimeMillis()

        } catch (e: Exception) {
            Log.e(TAG, "Error during age check", e)
        }
    }

    // Legacy alias for backwards compatibility
    private suspend fun performSAGECheck() = performAgeCheck()

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

        // Cancel health check watchdog
        healthCheckJob?.cancel()
        healthCheckJob = null

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
