package com.diabetesscreenreader.service

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity that shows over lockscreen and dismisses it for glucose reading.
 * Uses modern Android APIs (API 27+) for reliable lockscreen dismissal.
 *
 * IMPORTANT: Only works with "Slide to Unlock" - NOT with PIN/Password/Fingerprint!
 */
class LockscreenReadingActivity : Activity() {

    companion object {
        private const val TAG = "LockscreenReading"
        private const val WAKELOCK_TIMEOUT_MS = 30_000L // 30 seconds max
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "LockscreenReadingActivity created")

        // Step 1: Acquire WakeLock to turn on screen
        acquireWakeLock()

        // Step 2: Set modern lockscreen flags (API 27+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Step 3: Legacy flags for older devices (kept for compatibility)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Step 4: Request keyguard dismissal using modern API
        requestKeyguardDismissal()
    }

    /**
     * Acquires a WakeLock to turn on the screen.
     * Uses ACQUIRE_CAUSES_WAKEUP flag to wake the device.
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DiabetesScreenReader:LockscreenWake"
            )

            // Acquire with timeout to prevent battery drain
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            Log.d(TAG, "WakeLock acquired with ${WAKELOCK_TIMEOUT_MS}ms timeout")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    /**
     * Releases the WakeLock if held.
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        wakeLock = null
    }

    /**
     * Requests keyguard dismissal using the modern API (API 26+).
     * Falls back to launching the app directly on older devices.
     */
    private fun requestKeyguardDismissal() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Modern approach: Use requestDismissKeyguard with callback
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.d(TAG, "Keyguard dismissed successfully")
                    onKeyguardDismissed()
                }

                override fun onDismissCancelled() {
                    Log.w(TAG, "Keyguard dismissal was cancelled by user")
                    finishAndCleanup()
                }

                override fun onDismissError() {
                    Log.e(TAG, "Keyguard dismissal error - might require PIN/Password")
                    finishAndCleanup()
                }
            })
        } else {
            // Fallback for older devices
            Log.d(TAG, "Using legacy approach for pre-API 26 device")
            scope.launch {
                delay(1000) // Wait for screen to turn on
                onKeyguardDismissed()
            }
        }
    }

    /**
     * Called when keyguard is successfully dismissed.
     * Launches CamAPS FX and finishes the activity.
     */
    private fun onKeyguardDismissed() {
        scope.launch {
            try {
                // Launch CamAPS FX
                launchCamAPSFX()

                // Give the accessibility service time to detect and read the app
                delay(2000)

                Log.d(TAG, "Reading initiated - finishing activity")
            } catch (e: Exception) {
                Log.e(TAG, "Error after keyguard dismissed", e)
            } finally {
                finishAndCleanup()
            }
        }
    }

    /**
     * Launches the CamAPS FX app.
     */
    private fun launchCamAPSFX() {
        Log.d(TAG, "Launching CamAPS FX...")

        // Try multiple known package names for CamAPS FX variants
        val packageNames = listOf(
            "com.camdiab.fx_alert.mgdl",
            "com.camdiab.fx_alert.mmol",
            "com.camdiab.fx.camaps"
        )

        for (packageName in packageNames) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(launchIntent)
                Log.d(TAG, "CamAPS FX launched: $packageName")
                return
            }
        }

        Log.e(TAG, "Could not find any CamAPS FX variant to launch")
    }

    /**
     * Cleans up resources and finishes the activity.
     */
    private fun finishAndCleanup() {
        releaseWakeLock()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        scope.cancel()
        Log.d(TAG, "LockscreenReadingActivity destroyed")
    }
}
