package com.diabetesscreenreader.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.diabetesscreenreader.DiabetesScreenReaderApp
import com.diabetesscreenreader.R
import com.diabetesscreenreader.data.GlucoseRepository
import com.diabetesscreenreader.data.GlucoseUnit
import com.diabetesscreenreader.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DataCollectionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null

    private val app: DiabetesScreenReaderApp
        get() = application as DiabetesScreenReaderApp

    private val repository: GlucoseRepository by lazy {
        GlucoseRepository(
            app.database.glucoseDao(),
            app.nightscoutApi,
            app.preferencesManager
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DataCollectionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "DataCollectionService started")

        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopSelf()
            ACTION_SYNC_NOW -> syncNow()
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(DiabetesScreenReaderApp.NOTIFICATION_ID, notification)

        // Start periodic sync
        startPeriodicSync()
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            while (isActive) {
                try {
                    // Sync unuploaded readings to Nightscout
                    val result = repository.syncUnuploadedReadings()
                    result.onSuccess { count ->
                        if (count > 0) {
                            Log.d(TAG, "Synced $count readings to Nightscout")
                            updateNotification()
                        }
                    }.onFailure { error ->
                        Log.e(TAG, "Sync failed: ${error.message}")
                    }

                    // Cleanup old data periodically
                    repository.cleanupOldData()

                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic sync", e)
                }

                // Wait for next sync interval
                val intervalMinutes = app.preferencesManager.readingIntervalMinutes.first()
                delay(intervalMinutes * 60 * 1000L)
            }
        }
    }

    private fun syncNow() {
        serviceScope.launch {
            repository.syncUnuploadedReadings()
            updateNotification()
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DiabetesScreenReaderApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        serviceScope.launch {
            val latestReading = repository.getLatestReadingSync()
            val unit = app.preferencesManager.getGlucoseUnitSync()

            val contentText = if (latestReading != null) {
                val value = latestReading.getFormattedValue(unit)
                val trend = latestReading.trend.arrow
                "$value ${unit.getDisplayString()} $trend"
            } else {
                getString(R.string.notification_text)
            }

            val notification = NotificationCompat.Builder(
                this@DataCollectionService,
                DiabetesScreenReaderApp.NOTIFICATION_CHANNEL_ID
            )
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(DiabetesScreenReaderApp.NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        serviceScope.cancel()
        Log.d(TAG, "DataCollectionService destroyed")
    }

    companion object {
        private const val TAG = "DataCollectionService"
        const val ACTION_START = "com.diabetesscreenreader.action.START"
        const val ACTION_STOP = "com.diabetesscreenreader.action.STOP"
        const val ACTION_SYNC_NOW = "com.diabetesscreenreader.action.SYNC_NOW"
    }
}
