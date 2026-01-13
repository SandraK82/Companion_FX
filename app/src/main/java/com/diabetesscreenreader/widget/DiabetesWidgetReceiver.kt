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
        android.util.Log.d("DiabetesWidget", "updateWidget called for ID: $appWidgetId")
        scope.launch {
            try {
                val app = context.applicationContext as DiabetesScreenReaderApp
                val repository = app.repository

                val latestReading = repository.getLatestReadingSync()
                val readings = repository.getLatestReadingsSync(144) // Last 12 hours at 5min intervals
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

                    // Update status labels
                    latestReading.basalRate?.let {
                        views.setTextViewText(R.id.basal_rate, String.format("%.2f IE/h", it))
                    } ?: views.setTextViewText(R.id.basal_rate, "--")

                    latestReading.activeInsulin?.let {
                        views.setTextViewText(R.id.active_insulin, String.format("%.2f IE", it))
                    } ?: views.setTextViewText(R.id.active_insulin, "--")

                    latestReading.reservoir?.let {
                        views.setTextViewText(R.id.reservoir, String.format("%d IE", it.toInt()))
                    } ?: views.setTextViewText(R.id.reservoir, "--")

                    latestReading.pumpBattery?.let {
                        views.setTextViewText(R.id.battery, "$it%")
                    } ?: views.setTextViewText(R.id.battery, "--")

                    // Generate and set graph
                    if (readings.isNotEmpty()) {
                        android.util.Log.d("DiabetesWidget", "Generating graph with ${readings.size} readings")
                        val graphBitmap = generateGraphBitmap(
                            context, readings.reversed(), unit,
                            lowThreshold, highThreshold, latestReading
                        )
                        android.util.Log.d("DiabetesWidget", "Graph bitmap generated: ${graphBitmap.width}x${graphBitmap.height}")
                        views.setImageViewBitmap(R.id.graph_image, graphBitmap)
                    } else {
                        android.util.Log.w("DiabetesWidget", "No readings for graph")
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
        highThreshold: Int,
        latestReading: GlucoseReading
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

        // Draw glucose line (blue thin line)
        val linePaint = Paint().apply {
            color = Color.parseColor("#2196F3") // Blue
            strokeWidth = 2f // Thin line
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

        // Draw basal rate line (thin line at bottom)
        val basalReadings = readings.filter { it.basalRate != null }
        if (basalReadings.isNotEmpty()) {
            // Find basal rate range for scaling
            val basalValues = basalReadings.mapNotNull { it.basalRate }
            val maxBasalRate = basalValues.maxOrNull() ?: 3.0
            val basalScaleFactor = 30.0 / maxBasalRate // Max 30 pixels height for basal rate

            val basalPaint = Paint().apply {
                color = Color.parseColor("#9C27B0") // Purple
                strokeWidth = 1.5f // Thin line
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.SQUARE  // Sharp ends
                strokeJoin = Paint.Join.MITER  // Sharp corners
            }

            val basalPath = Path()
            var lastX = 0f
            var lastY = 0f

            basalReadings.forEachIndexed { index, reading ->
                val x = padding + ((reading.timestamp - readings.first().timestamp).toFloat() / timeRange * graphWidth)
                val basalY = height - padding - (reading.basalRate!! * basalScaleFactor).toFloat()

                if (index == 0) {
                    basalPath.moveTo(x, basalY)
                    lastX = x
                    lastY = basalY
                } else {
                    // Draw step: horizontal first, then vertical (sharp corners)
                    basalPath.lineTo(x, lastY)  // Horizontal to new x
                    basalPath.lineTo(x, basalY)  // Vertical to new y
                    lastX = x
                    lastY = basalY
                }
            }

            // Extend last value to end of graph
            if (basalReadings.isNotEmpty()) {
                basalPath.lineTo(width - padding, lastY)
            }

            canvas.drawPath(basalPath, basalPaint)
        }

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

            canvas.drawCircle(x, y, 3f, dotPaint) // Smaller dots for 12h view
        }

        // Draw bolus markers for all boluses in the history
        val bolusPaint = Paint().apply {
            color = Color.parseColor("#FF5722") // Orange/Red
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val markerPaint = Paint().apply {
            color = Color.parseColor("#FF5722")
            style = Paint.Style.FILL
            isAntiAlias = true
            textSize = 12f
            textAlign = Paint.Align.CENTER
        }

        // Track drawn boluses to avoid duplicates
        val drawnBolusTimestamps = mutableSetOf<Long>()

        readings.forEach { reading ->
            reading.bolusAmount?.let { bolusAmount ->
                reading.bolusMinutesAgo?.let { minutesAgo ->
                    val bolusTimestamp = reading.timestamp - (minutesAgo * 60 * 1000)

                    // Only draw if within graph range and not already drawn
                    if (bolusTimestamp >= readings.first().timestamp &&
                        bolusTimestamp <= readings.last().timestamp &&
                        !drawnBolusTimestamps.contains(bolusTimestamp)) {

                        drawnBolusTimestamps.add(bolusTimestamp)

                        val x = padding + ((bolusTimestamp - readings.first().timestamp).toFloat() / timeRange * graphWidth)

                        // Draw vertical line for bolus
                        canvas.drawLine(x, padding, x, height - padding, bolusPaint)

                        // Draw bolus marker at top
                        canvas.drawCircle(x, padding + 10f, 6f, markerPaint)

                        // Draw bolus amount text (only if > 0.5 IE to avoid clutter)
                        if (bolusAmount >= 0.5) {
                            canvas.drawText(
                                String.format("%.1f", bolusAmount),
                                x,
                                padding + 25f,
                                markerPaint
                            )
                        }
                    }
                }
            }
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
