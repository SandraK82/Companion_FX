package com.diabetesscreenreader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.diabetesscreenreader.data.AppDatabase
import com.diabetesscreenreader.data.GlucoseRepository
import com.diabetesscreenreader.data.PreferencesManager
import com.diabetesscreenreader.network.NightscoutApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DiabetesScreenReaderApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(this)
    }

    val nightscoutApi: NightscoutApi by lazy {
        NightscoutApi(preferencesManager)
    }

    val repository: GlucoseRepository by lazy {
        GlucoseRepository(database.glucoseDao(), nightscoutApi, preferencesManager)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Glucose Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Benachrichtigungen bei kritischen Glukosewerten"
            enableVibration(true)
        }

        val lockscreenChannel = NotificationChannel(
            LOCKSCREEN_READING_CHANNEL_ID,
            "Sperrbildschirm-Abfragen",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ermöglicht Glukose-Abfragen über den Sperrbildschirm"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(alertChannel)
        notificationManager.createNotificationChannel(lockscreenChannel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "diabetes_monitoring"
        const val ALERT_CHANNEL_ID = "glucose_alerts"
        const val LOCKSCREEN_READING_CHANNEL_ID = "lockscreen_reading"
        const val NOTIFICATION_ID = 1001
        const val LOCKSCREEN_NOTIFICATION_ID = 1002

        @Volatile
        private var instance: DiabetesScreenReaderApp? = null

        fun getInstance(): DiabetesScreenReaderApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
