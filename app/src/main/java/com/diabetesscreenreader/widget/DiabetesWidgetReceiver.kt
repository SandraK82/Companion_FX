package com.diabetesscreenreader.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.RemoteViews
import com.diabetesscreenreader.DiabetesScreenReaderApp
import com.diabetesscreenreader.R
import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseRepository
import com.diabetesscreenreader.data.GlucoseUnit
import com.diabetesscreenreader.data.RangeStatus
import com.diabetesscreenreader.service.DiabetesAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DiabetesWidgetReceiver : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == DiabetesAccessibilityService.ACTION_GLUCOSE_UPDATE ||
            intent.action == ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, DiabetesWidgetReceiver::class.java)
            )
            for (widgetId in widgetIds) {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        scope.launch {
            try {
                val app = context.applicationContext as DiabetesScreenReaderApp
                val repository = GlucoseRepository(
                    app.database.glucoseDao(),
                    app.nightscoutApi,
                    app.preferencesManager
                )

                val latestReading = repository.getLatestReadingSync()
                val readings = repository.getLatestReadingsSync(48) // Last ~4 hours at 5min intervals
                val unit = app.preferencesManager.getGlucoseUnitSync()
                val lowThreshold = app.preferencesManager.getLowThresholdSync()
                val highThreshold = app.preferencesManager.getHighThresholdSync()
                val nightscoutEnabled = app.preferencesManager.getNightscoutEnabledSync()

                val views = RemoteViews(context.packageName, R.layout.widget_layout)

                if (latestReading != null) {
                    // Update glucose value
                    val formattedValue = latestReading.getFormattedValue(unit)
                    views.setTextViewText(R.id.glucose_value, formattedValue)
                    views.setTextViewText(R.id.glucose_unit, unit.getDisplayString())
                    views.setTextViewText(R.id.trend_arrow, latestReading.trend.arrow)

                    // Set color based on range
                    val rangeStatus = latestReading.isInRange(lowThreshold.toDouble(), highThreshold.toDouble())
                    val valueColor = when (rangeStatus) {
                        RangeStatus.LOW -> context.getColor(R.color.glucose_low)
                        RangeStatus.HIGH -> context.getColor(R.color.glucose_high)
                        RangeStatus.IN_RANGE -> context.getColor(R.color.glucose_normal)
                    }
                    views.setTextColor(R.id.glucose_value, valueColor)

                    // Update time ago
                    val minutesAgo = (System.currentTimeMillis() - latestReading.timestamp) / 60000
                    val timeAgoText = when {
                        minutesAgo < 1 -> "jetzt"
                        minutesAgo < 60 -> "$minutesAgo min"
                        else -> "${minutesAgo / 60}h ${minutesAgo % 60}min"
                    }
                    views.setTextViewText(R.id.time_ago, timeAgoText)

                    // Update range info
                    views.setTextViewText(R.id.range_info, "$lowThreshold-$highThreshold ${unit.getDisplayString()}")

                    // Update Nightscout sync status
                    val syncStatus = if (nightscoutEnabled) {
                        if (latestReading.uploadedToNightscout) "Nightscout ✓" else "Nightscout ⏳"
                    } else {
                        ""
                    }
                    views.setTextViewText(R.id.last_sync, syncStatus)

                    // Generate and set graph
                    if (readings.isNotEmpty()) {
                        val graphBitmap = generateGraphBitmap(
                            context, readings.reversed(), unit,
                            lowThreshold, highThreshold
                        )
                        views.setImageViewBitmap(R.id.graph_image, graphBitmap)
                    }
                } else {
                    views.setTextViewText(R.id.glucose_value, "--")
                    views.setTextViewText(R.id.trend_arrow, "")
                    views.setTextViewText(R.id.time_ago, context.getString(R.string.no_data))
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateGraphBitmap(
        context: Context,
        readings: List<GlucoseReading>,
        unit: GlucoseUnit,
        lowThreshold: Int,
        highThreshold: Int
    ): Bitmap {
        val width = 400
        val height = 150
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        canvas.drawColor(Color.TRANSPARENT)

        if (readings.isEmpty()) return bitmap

        val padding = 10f
        val graphWidth = width - 2 * padding
        val graphHeight = height - 2 * padding

        // Find value range
        val values = readings.map { it.getValueInUnit(unit) }
        val minValue = (values.minOrNull() ?: 40.0).coerceAtMost(lowThreshold.toDouble() - 10)
        val maxValue = (values.maxOrNull() ?: 400.0).coerceAtLeast(highThreshold.toDouble() + 10)
        val valueRange = maxValue - minValue

        // Draw threshold zones
        val zonePaint = Paint().apply {
            style = Paint.Style.FILL
        }

        // Low zone
        zonePaint.color = context.getColor(R.color.graph_low_zone)
        val lowY = height - padding - ((lowThreshold - minValue) / valueRange * graphHeight).toFloat()
        canvas.drawRect(padding, lowY, width - padding, height - padding, zonePaint)

        // High zone
        zonePaint.color = context.getColor(R.color.graph_high_zone)
        val highY = height - padding - ((highThreshold - minValue) / valueRange * graphHeight).toFloat()
        canvas.drawRect(padding, padding, width - padding, highY, zonePaint)

        // Normal zone
        zonePaint.color = context.getColor(R.color.graph_normal_zone)
        canvas.drawRect(padding, highY, width - padding, lowY, zonePaint)

        // Draw glucose line
        val linePaint = Paint().apply {
            color = context.getColor(R.color.graph_line)
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        val timeRange = readings.last().timestamp - readings.first().timestamp
        if (timeRange <= 0) return bitmap

        readings.forEachIndexed { index, reading ->
            val x = padding + ((reading.timestamp - readings.first().timestamp).toFloat() / timeRange * graphWidth)
            val normalizedValue = (reading.getValueInUnit(unit) - minValue) / valueRange
            val y = height - padding - (normalizedValue * graphHeight).toFloat()

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, linePaint)

        // Draw dots at each point
        val dotPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        readings.forEach { reading ->
            val x = padding + ((reading.timestamp - readings.first().timestamp).toFloat() / timeRange * graphWidth)
            val normalizedValue = (reading.getValueInUnit(unit) - minValue) / valueRange
            val y = height - padding - (normalizedValue * graphHeight).toFloat()

            val rangeStatus = reading.isInRange(lowThreshold.toDouble(), highThreshold.toDouble())
            dotPaint.color = when (rangeStatus) {
                RangeStatus.LOW -> context.getColor(R.color.glucose_low)
                RangeStatus.HIGH -> context.getColor(R.color.glucose_high)
                RangeStatus.IN_RANGE -> context.getColor(R.color.glucose_normal)
            }

            canvas.drawCircle(x, y, 4f, dotPaint)
        }

        return bitmap
    }

    companion object {
        const val ACTION_REFRESH = "com.diabetesscreenreader.widget.REFRESH"

        fun requestUpdate(context: Context) {
            val intent = Intent(context, DiabetesWidgetReceiver::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }
    }
}
