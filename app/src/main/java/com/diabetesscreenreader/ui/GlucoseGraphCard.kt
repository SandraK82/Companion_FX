package com.diabetesscreenreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diabetesscreenreader.R
import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseUnit
import com.diabetesscreenreader.data.RangeStatus

@Composable
fun GlucoseGraphCard(
    readings: List<GlucoseReading>,
    latestReading: GlucoseReading,
    unit: GlucoseUnit,
    lowThreshold: Int,
    highThreshold: Int
) {
    val context = LocalContext.current

    val graphBitmap = remember(readings, unit, lowThreshold, highThreshold) {
        generateGraphBitmap(
            context = context,
            readings = readings.reversed(),
            unit = unit,
            lowThreshold = lowThreshold,
            highThreshold = highThreshold,
            latestReading = latestReading
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Glucose-Verlauf (12h)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Image(
                bitmap = graphBitmap.asImageBitmap(),
                contentDescription = "Glucose Graph",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentScale = ContentScale.FillBounds
            )
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
    val width = 800
    val height = 400
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Background
    canvas.drawColor(android.graphics.Color.WHITE)

    if (readings.isEmpty()) return bitmap

    val padding = 20f
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
        color = android.graphics.Color.parseColor("#2196F3") // Blue
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

    // Draw basal rate line (thin line at bottom)
    val basalReadings = readings.filter { it.basalRate != null }
    if (basalReadings.isNotEmpty()) {
        // Find basal rate range for scaling
        val basalValues = basalReadings.mapNotNull { it.basalRate }
        val maxBasalRate = basalValues.maxOrNull() ?: 3.0
        val basalScaleFactor = 60.0 / maxBasalRate // Max 60 pixels height for basal rate

        val basalPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#9C27B0") // Purple
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
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

        canvas.drawCircle(x, y, 4f, dotPaint)
    }

    // Draw bolus markers for all boluses in the history
    val bolusPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#FF5722") // Orange/Red
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    val markerPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#FF5722")
        style = Paint.Style.FILL
        isAntiAlias = true
        textSize = 18f
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
                    canvas.drawCircle(x, padding + 15f, 8f, markerPaint)

                    // Draw bolus amount text (only if > 0.5 IE to avoid clutter)
                    if (bolusAmount >= 0.5) {
                        canvas.drawText(
                            String.format("%.1f", bolusAmount),
                            x,
                            padding + 35f,
                            markerPaint
                        )
                    }
                }
            }
        }
    }

    return bitmap
}
