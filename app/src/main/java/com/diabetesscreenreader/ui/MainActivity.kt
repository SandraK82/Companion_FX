package com.diabetesscreenreader.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diabetesscreenreader.DiabetesScreenReaderApp
import com.diabetesscreenreader.data.GlucoseReading
import com.diabetesscreenreader.data.GlucoseRepository
import com.diabetesscreenreader.data.GlucoseUnit
import com.diabetesscreenreader.data.RangeStatus
import com.diabetesscreenreader.service.DataCollectionService
import com.diabetesscreenreader.service.DiabetesAccessibilityService
import com.diabetesscreenreader.ui.theme.DiabetesScreenReaderTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val app: DiabetesScreenReaderApp
        get() = application as DiabetesScreenReaderApp

    private val repository: GlucoseRepository by lazy {
        GlucoseRepository(
            app.database.glucoseDao(),
            app.nightscoutApi,
            app.preferencesManager
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DiabetesScreenReaderTheme {
                MainScreen(
                    repository = repository,
                    preferencesManager = app.preferencesManager,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onToggleService = { enabled -> toggleService(enabled) }
                )
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun toggleService(enabled: Boolean) {
        val serviceIntent = Intent(this, DataCollectionService::class.java)
        if (enabled) {
            serviceIntent.action = DataCollectionService.ACTION_START
            startForegroundService(serviceIntent)
        } else {
            serviceIntent.action = DataCollectionService.ACTION_STOP
            startService(serviceIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: GlucoseRepository,
    preferencesManager: com.diabetesscreenreader.data.PreferencesManager,
    onOpenSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleService: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()

    val latestReading by repository.latestReading.collectAsStateWithLifecycle(initialValue = null)
    val recentReadings by repository.getLatestReadings(24).collectAsStateWithLifecycle(initialValue = emptyList())
    val glucoseUnit by preferencesManager.glucoseUnit.collectAsStateWithLifecycle(initialValue = GlucoseUnit.MG_DL)
    val lowThreshold by preferencesManager.lowThreshold.collectAsStateWithLifecycle(initialValue = 70)
    val highThreshold by preferencesManager.highThreshold.collectAsStateWithLifecycle(initialValue = 180)
    val serviceEnabled by preferencesManager.serviceEnabled.collectAsStateWithLifecycle(initialValue = false)

    val isAccessibilityEnabled = DiabetesAccessibilityService.isServiceRunning()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diabetes Screen Reader") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Service Status Card
            ServiceStatusCard(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isServiceEnabled = serviceEnabled,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onToggleService = { enabled ->
                    scope.launch {
                        preferencesManager.setServiceEnabled(enabled)
                        onToggleService(enabled)
                    }
                }
            )

            // Current Glucose Card
            CurrentGlucoseCard(
                reading = latestReading,
                unit = glucoseUnit,
                lowThreshold = lowThreshold,
                highThreshold = highThreshold
            )

            // Statistics Card
            if (recentReadings.isNotEmpty()) {
                StatisticsCard(
                    readings = recentReadings,
                    unit = glucoseUnit,
                    lowThreshold = lowThreshold,
                    highThreshold = highThreshold
                )
            }

            // Recent Readings Card
            if (recentReadings.isNotEmpty()) {
                RecentReadingsCard(
                    readings = recentReadings.take(10),
                    unit = glucoseUnit,
                    lowThreshold = lowThreshold,
                    highThreshold = highThreshold
                )
            }
        }
    }
}

@Composable
fun ServiceStatusCard(
    isAccessibilityEnabled: Boolean,
    isServiceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleService: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAccessibilityEnabled && isServiceEnabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Service Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Accessibility Service",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (isAccessibilityEnabled) "Aktiviert" else "Deaktiviert",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isAccessibilityEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                }

                if (!isAccessibilityEnabled) {
                    Button(onClick = onOpenAccessibilitySettings) {
                        Text("Aktivieren")
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Hintergrund-Service",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (isServiceEnabled) "Läuft" else "Gestoppt",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isServiceEnabled) Color(0xFF4CAF50) else Color(0xFF757575)
                    )
                }

                Switch(
                    checked = isServiceEnabled,
                    onCheckedChange = onToggleService,
                    enabled = isAccessibilityEnabled
                )
            }
        }
    }
}

@Composable
fun CurrentGlucoseCard(
    reading: GlucoseReading?,
    unit: GlucoseUnit,
    lowThreshold: Int,
    highThreshold: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Aktueller Glukosewert",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (reading != null) {
                val rangeStatus = reading.isInRange(lowThreshold.toDouble(), highThreshold.toDouble())
                val valueColor = when (rangeStatus) {
                    RangeStatus.LOW -> Color(0xFFF44336)
                    RangeStatus.HIGH -> Color(0xFFFF9800)
                    RangeStatus.IN_RANGE -> Color(0xFF4CAF50)
                }

                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = reading.getFormattedValue(unit),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = valueColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = reading.trend.arrow,
                            fontSize = 32.sp,
                            color = valueColor
                        )
                        Text(
                            text = unit.getDisplayString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val minutesAgo = (System.currentTimeMillis() - reading.timestamp) / 60000
                val timeAgoText = when {
                    minutesAgo < 1 -> "Gerade eben"
                    minutesAgo < 60 -> "vor $minutesAgo Minuten"
                    else -> "vor ${minutesAgo / 60}h ${minutesAgo % 60}min"
                }

                Text(
                    text = timeAgoText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = reading.trend.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "--",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Keine Daten verfügbar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatisticsCard(
    readings: List<GlucoseReading>,
    unit: GlucoseUnit,
    lowThreshold: Int,
    highThreshold: Int
) {
    val values = readings.map { it.getValueInUnit(unit) }
    val average = values.average()
    val min = values.minOrNull() ?: 0.0
    val max = values.maxOrNull() ?: 0.0

    var lowCount = 0
    var inRangeCount = 0
    var highCount = 0

    readings.forEach { reading ->
        when (reading.isInRange(lowThreshold.toDouble(), highThreshold.toDouble())) {
            RangeStatus.LOW -> lowCount++
            RangeStatus.IN_RANGE -> inRangeCount++
            RangeStatus.HIGH -> highCount++
        }
    }

    val total = readings.size.toFloat()
    val lowPercent = if (total > 0) (lowCount / total * 100).toInt() else 0
    val inRangePercent = if (total > 0) (inRangeCount / total * 100).toInt() else 0
    val highPercent = if (total > 0) (highCount / total * 100).toInt() else 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Statistik (letzte ${readings.size} Messungen)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Durchschnitt",
                    value = when (unit) {
                        GlucoseUnit.MG_DL -> average.toInt().toString()
                        GlucoseUnit.MMOL_L -> String.format("%.1f", average)
                    }
                )
                StatItem(label = "Min", value = when (unit) {
                    GlucoseUnit.MG_DL -> min.toInt().toString()
                    GlucoseUnit.MMOL_L -> String.format("%.1f", min)
                })
                StatItem(label = "Max", value = when (unit) {
                    GlucoseUnit.MG_DL -> max.toInt().toString()
                    GlucoseUnit.MMOL_L -> String.format("%.1f", max)
                })
            }

            HorizontalDivider()

            Text(
                text = "Zeit im Bereich",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            // Time in range bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                if (lowPercent > 0) {
                    Box(
                        modifier = Modifier
                            .weight(lowPercent.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight()
                            .background(Color(0xFFF44336), RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                    )
                }
                if (inRangePercent > 0) {
                    Box(
                        modifier = Modifier
                            .weight(inRangePercent.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight()
                            .background(Color(0xFF4CAF50))
                    )
                }
                if (highPercent > 0) {
                    Box(
                        modifier = Modifier
                            .weight(highPercent.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight()
                            .background(Color(0xFFFF9800), RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Niedrig: $lowPercent%", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF44336))
                Text("Im Bereich: $inRangePercent%", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
                Text("Hoch: $highPercent%", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800))
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun RecentReadingsCard(
    readings: List<GlucoseReading>,
    unit: GlucoseUnit,
    lowThreshold: Int,
    highThreshold: Int
) {
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Letzte Messungen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            readings.forEach { reading ->
                val rangeStatus = reading.isInRange(lowThreshold.toDouble(), highThreshold.toDouble())
                val valueColor = when (rangeStatus) {
                    RangeStatus.LOW -> Color(0xFFF44336)
                    RangeStatus.HIGH -> Color(0xFFFF9800)
                    RangeStatus.IN_RANGE -> Color(0xFF4CAF50)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateFormat.format(Date(reading.timestamp)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = reading.getFormattedValue(unit),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = valueColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = reading.trend.arrow,
                            style = MaterialTheme.typography.bodyLarge,
                            color = valueColor
                        )
                    }
                }

                if (reading != readings.last()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}
