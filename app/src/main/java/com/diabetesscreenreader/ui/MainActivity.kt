package com.diabetesscreenreader.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0=Dashboard, 1=History, 2=Service, 3=Nightscout
    var selectedTimeRange by remember { mutableStateOf(0) } // 0=6h, 1=12h, 2=24h, 3=48h
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var currentTimeMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    // Check accessibility service status periodically
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = DiabetesAccessibilityService.isServiceRunning(context)
            kotlinx.coroutines.delay(1000) // Check every second
        }
    }

    // Update UI time every minute to refresh "X minutes ago" displays
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(60000) // Update every minute
        }
    }

    val latestReading by repository.latestReading.collectAsStateWithLifecycle(initialValue = null)
    val graphReadings by repository.getLatestReadings(144).collectAsStateWithLifecycle(initialValue = emptyList()) // 12h at 5min intervals
    val allReadings by repository.getAllReadingsFlow(500).collectAsStateWithLifecycle(initialValue = emptyList())
    val unuploadedReadings by repository.getUnuploadedReadingsFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    // Get readings based on selected time range
    val readingCount = when (selectedTimeRange) {
        0 -> 72    // 6h
        1 -> 144   // 12h
        2 -> 288   // 24h
        3 -> 576   // 48h
        else -> 144
    }
    val recentReadings by repository.getLatestReadings(readingCount).collectAsStateWithLifecycle(initialValue = emptyList())

    val glucoseUnit by preferencesManager.glucoseUnit.collectAsStateWithLifecycle(initialValue = GlucoseUnit.MG_DL)
    val lowThreshold by preferencesManager.lowThreshold.collectAsStateWithLifecycle(initialValue = 70)
    val highThreshold by preferencesManager.highThreshold.collectAsStateWithLifecycle(initialValue = 180)
    val serviceEnabled by preferencesManager.serviceEnabled.collectAsStateWithLifecycle(initialValue = false)

    // SAGE and IAGE
    val sensorStartTime by preferencesManager.sensorStartTime.collectAsStateWithLifecycle(initialValue = 0L)
    val sensorName by preferencesManager.sensorName.collectAsStateWithLifecycle(initialValue = "")
    val insulinFillTime by preferencesManager.insulinFillTime.collectAsStateWithLifecycle(initialValue = 0L)

    val timeRangeLabels = listOf("6h", "12h", "24h", "48h")

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
        ) {
            // Main Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Dashboard") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Historie") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Service") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Nightscout") }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> {
                    // Dashboard Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Current Glucose Card
                        CurrentGlucoseCard(
                            reading = latestReading,
                            unit = glucoseUnit,
                            lowThreshold = lowThreshold,
                            highThreshold = highThreshold,
                            currentTimeMillis = currentTimeMillis
                        )

                        // Graph Card
                        if (graphReadings.isNotEmpty() && latestReading != null) {
                            GlucoseGraphCard(
                                readings = graphReadings,
                                latestReading = latestReading!!,
                                unit = glucoseUnit,
                                lowThreshold = lowThreshold,
                                highThreshold = highThreshold
                            )
                        }

                        // CamAPS FX Status Card - only show if we have CamAPS FX specific data
                        latestReading?.let { reading ->
                            if (reading.basalRate != null || reading.activeInsulin != null ||
                                reading.reservoir != null || reading.pumpBattery != null) {
                                CamAPSFXStatusCard(reading = reading)
                            }
                        }

                        // SAGE/IAGE Card - show if we have sensor or insulin data
                        if (sensorStartTime > 0L || insulinFillTime > 0L) {
                            SageIageCard(
                                sensorStartTime = sensorStartTime,
                                sensorName = sensorName,
                                insulinFillTime = insulinFillTime,
                                currentTimeMillis = currentTimeMillis
                            )
                        }
                    }
                }
                1 -> {
                    // History Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Time Range Tabs
                        TabRow(
                            selectedTabIndex = selectedTimeRange,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            timeRangeLabels.forEachIndexed { index, label ->
                                Tab(
                                    selected = selectedTimeRange == index,
                                    onClick = { selectedTimeRange = index },
                                    text = { Text(label) }
                                )
                            }
                        }

                        // Statistics Card
                        if (recentReadings.isNotEmpty()) {
                            StatisticsCard(
                                readings = recentReadings,
                                unit = glucoseUnit,
                                lowThreshold = lowThreshold,
                                highThreshold = highThreshold,
                                timeRangeLabel = timeRangeLabels[selectedTimeRange]
                            )
                        }

                        // Recent Readings Card
                        if (recentReadings.isNotEmpty()) {
                            RecentReadingsCard(
                                readings = recentReadings.take(20),
                                unit = glucoseUnit,
                                lowThreshold = lowThreshold,
                                highThreshold = highThreshold
                            )
                        }
                    }
                }
                2 -> {
                    // Service Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
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
                    }
                }
                3 -> {
                    // Nightscout Tab
                    NightscoutTab(
                        allReadings = allReadings,
                        unuploadedReadings = unuploadedReadings,
                        unit = glucoseUnit,
                        lowThreshold = lowThreshold,
                        highThreshold = highThreshold,
                        onRetryUpload = {
                            scope.launch {
                                repository.syncUnuploadedReadings()
                            }
                        }
                    )
                }
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
    highThreshold: Int,
    currentTimeMillis: Long
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

                val minutesAgo = (currentTimeMillis - reading.timestamp) / 60000
                val timeAgoText = when {
                    minutesAgo < 1 -> "Gerade eben"
                    minutesAgo == 1L -> "vor einer Minute"
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
fun CamAPSFXStatusCard(reading: GlucoseReading) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "CamAPS FX Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusItem(
                    label = "Basalrate",
                    value = reading.basalRate?.let { String.format("%.2f IE/h", it) } ?: "--"
                )
                StatusItem(
                    label = "Aktives Insulin",
                    value = reading.activeInsulin?.let { String.format("%.2f IE", it) } ?: "--"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusItem(
                    label = "Reservoir",
                    value = reading.reservoir?.let { "${it.toInt()} IE" } ?: "--",
                    warning = reading.reservoir?.let { it < 50 } ?: false
                )
                StatusItem(
                    label = "Batterie",
                    value = reading.pumpBattery?.let { "$it%" } ?: "--",
                    warning = reading.pumpBattery?.let { it < 30 } ?: false
                )
            }

            if (reading.bolusAmount != null && reading.bolusMinutesAgo != null) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Letzter Bolus",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${String.format("%.2f", reading.bolusAmount)} IE vor ${reading.bolusMinutesAgo} min",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (reading.sensorDataMinutesAgo != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sensordaten",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "vor ${reading.sensorDataMinutesAgo} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (reading.sensorDataMinutesAgo > 10) Color(0xFFFF9800) else Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusItem(label: String, value: String, warning: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (warning) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SageIageCard(
    sensorStartTime: Long,
    sensorName: String,
    insulinFillTime: Long,
    currentTimeMillis: Long
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Sensor & Insulin",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // SAGE (Sensor Age)
                if (sensorStartTime > 0L) {
                    val sageMs = currentTimeMillis - sensorStartTime
                    val sageDays = sageMs / (24 * 60 * 60 * 1000)
                    val sageHours = (sageMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
                    val sageText = "${sageDays}d ${sageHours}h"
                    // Warning if sensor is older than 10 days (typical Libre 3 limit is 14 days)
                    val sageWarning = sageDays >= 10

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sageText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (sageWarning) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "SAGE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (sensorName.isNotBlank()) {
                            Text(
                                text = sensorName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // IAGE (Insulin Age)
                if (insulinFillTime > 0L) {
                    val iageMs = currentTimeMillis - insulinFillTime
                    val iageDays = iageMs / (24 * 60 * 60 * 1000)
                    val iageHours = (iageMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
                    val iageText = "${iageDays}d ${iageHours}h"
                    // Warning if insulin is older than 3 days (typical recommendation)
                    val iageWarning = iageDays >= 3

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = iageText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (iageWarning) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "IAGE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Reservoir",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatisticsCard(
    readings: List<GlucoseReading>,
    unit: GlucoseUnit,
    lowThreshold: Int,
    highThreshold: Int,
    timeRangeLabel: String
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
                text = "Statistik ($timeRangeLabel - ${readings.size} Messungen)",
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

                Column(modifier = Modifier.fillMaxWidth()) {
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

                    // Show events if available
                    val events = mutableListOf<String>()
                    reading.basalRate?.let { events.add("Basal: $it IE/h") }
                    reading.bolusAmount?.let {
                        val timeAgo = reading.bolusMinutesAgo?.let { " (vor ${it}min)" } ?: ""
                        events.add("Bolus: $it IE$timeAgo")
                    }
                    reading.activeInsulin?.let { events.add("IOB: $it IE") }
                    reading.reservoir?.let { events.add("Reservoir: $it IE") }
                    reading.pumpBattery?.let { events.add("Pumpe: $it%") }

                    if (events.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = events.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
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

@Composable
fun NightscoutTab(
    allReadings: List<GlucoseReading>,
    unuploadedReadings: List<GlucoseReading>,
    unit: GlucoseUnit,
    lowThreshold: Int,
    highThreshold: Int,
    onRetryUpload: () -> Unit
) {
    val dateTimeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Statistics Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Nightscout Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatusItem(
                        label = "Gesamt",
                        value = allReadings.size.toString()
                    )
                    StatusItem(
                        label = "Hochgeladen",
                        value = (allReadings.size - unuploadedReadings.size).toString()
                    )
                    StatusItem(
                        label = "Warteschlange",
                        value = unuploadedReadings.size.toString(),
                        warning = unuploadedReadings.isNotEmpty()
                    )
                }

                if (unuploadedReadings.isNotEmpty()) {
                    HorizontalDivider()
                    Button(
                        onClick = onRetryUpload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Warteschlange erneut hochladen (${unuploadedReadings.size})")
                    }
                }
            }
        }

        // All Readings List
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Alle Messwerte (${allReadings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (allReadings.isEmpty()) {
                    Text(
                        text = "Keine Messwerte vorhanden",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    allReadings.forEach { reading ->
                        val rangeStatus = reading.isInRange(lowThreshold.toDouble(), highThreshold.toDouble())
                        val valueColor = when (rangeStatus) {
                            RangeStatus.LOW -> Color(0xFFF44336)
                            RangeStatus.HIGH -> Color(0xFFFF9800)
                            RangeStatus.IN_RANGE -> Color(0xFF4CAF50)
                        }

                        val uploadedColor = if (reading.uploadedToNightscout) {
                            Color(0xFF4CAF50)
                        } else {
                            Color(0xFFFF9800)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dateTimeFormat.format(Date(reading.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
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

                            // Upload Status Badge
                            Surface(
                                color = uploadedColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (reading.uploadedToNightscout) "✓" else "⏳",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = uploadedColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (reading != allReadings.last()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
