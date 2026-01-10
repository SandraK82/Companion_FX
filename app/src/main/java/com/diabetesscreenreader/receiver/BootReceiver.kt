package com.diabetesscreenreader.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.diabetesscreenreader.DiabetesScreenReaderApp
import com.diabetesscreenreader.service.DataCollectionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d(TAG, "Boot completed, checking if service should start")

            val app = context.applicationContext as DiabetesScreenReaderApp

            runBlocking {
                val serviceEnabled = app.preferencesManager.serviceEnabled.first()
                if (serviceEnabled) {
                    Log.d(TAG, "Starting DataCollectionService after boot")
                    val serviceIntent = Intent(context, DataCollectionService::class.java).apply {
                        action = DataCollectionService.ACTION_START
                    }
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
