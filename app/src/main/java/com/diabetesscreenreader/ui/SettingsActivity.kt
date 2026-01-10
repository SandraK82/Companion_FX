package com.diabetesscreenreader.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diabetesscreenreader.DiabetesScreenReaderApp
import com.diabetesscreenreader.data.GlucoseUnit
import com.diabetesscreenreader.service.DiabetesAccessibilityService
import com.diabetesscreenreader.ui.theme.DiabetesScreenReaderTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private val app: DiabetesScreenReaderApp
        get() = application as DiabetesScreenReaderApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DiabetesScreenReaderTheme {
                SettingsScreen(
                    preferencesManager = app.preferencesManager,
                    nightscoutApi = app.nightscoutApi,
                    onBack = { finish() },
                    installedApps = getInstalledApps()
                )
            }
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return apps
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .mapNotNull { appInfo ->
                try {
                    AppInfo(
                        name = pm.getApplicationLabel(appInfo).toString(),
                        packageName = appInfo.packageName
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.name.lowercase() }
    }
}

data class AppInfo(
    val name: String,
    val packageName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferencesManager: com.diabetesscreenreader.data.PreferencesManager,
    nightscoutApi: com.diabetesscreenreader.network.NightscoutApi,
    onBack: () -> Unit,
    installedApps: List<AppInfo>
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Collect preferences
    val nightscoutUrl by preferencesManager.nightscoutUrl.collectAsStateWithLifecycle(initialValue = "")
    val nightscoutApiSecret by preferencesManager.nightscoutApiSecret.collectAsStateWithLifecycle(initialValue = "")
    val nightscoutEnabled by preferencesManager.nightscoutEnabled.collectAsStateWithLifecycle(initialValue = false)
    val targetAppPackage by preferencesManager.targetAppPackage.collectAsStateWithLifecycle(initialValue = "")
    val readingInterval by preferencesManager.readingIntervalMinutes.collectAsStateWithLifecycle(initialValue = 5)
    val glucoseUnit by preferencesManager.glucoseUnit.collectAsStateWithLifecycle(initialValue = GlucoseUnit.MG_DL)
    val lowThreshold by preferencesManager.lowThreshold.collectAsStateWithLifecycle(initialValue = 70)
    val highThreshold by preferencesManager.highThreshold.collectAsStateWithLifecycle(initialValue = 180)
    val onlyReadWhenInactive by preferencesManager.onlyReadWhenAppInactive.collectAsStateWithLifecycle(initialValue = true)

    // Local state for editing
    var editUrl by remember(nightscoutUrl) { mutableStateOf(nightscoutUrl) }
    var editApiSecret by remember(nightscoutApiSecret) { mutableStateOf(nightscoutApiSecret) }
    var editLowThreshold by remember(lowThreshold) { mutableStateOf(lowThreshold.toString()) }
    var editHighThreshold by remember(highThreshold) { mutableStateOf(highThreshold.toString()) }

    var showAppPicker by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            // Nightscout Settings
            Text(
                text = "Nightscout",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Nightscout aktivieren")
                        Switch(
                            checked = nightscoutEnabled,
                            onCheckedChange = {
                                scope.launch {
                                    preferencesManager.setNightscoutEnabled(it)
                                }
                            }
                        )
                    }

                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("Nightscout URL") },
                        placeholder = { Text("https://your-nightscout.herokuapp.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = editApiSecret,
                        onValueChange = { editApiSecret = it },
                        label = { Text("API Secret") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    preferencesManager.setNightscoutUrl(editUrl)
                                    preferencesManager.setNightscoutApiSecret(editApiSecret)
                                    Toast.makeText(context, "Gespeichert", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Speichern")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isTestingConnection = true
                                    preferencesManager.setNightscoutUrl(editUrl)
                                    preferencesManager.setNightscoutApiSecret(editApiSecret)

                                    val result = nightscoutApi.testConnection()
                                    isTestingConnection = false

                                    val message = if (result.isSuccess) {
                                        "Verbindung erfolgreich!"
                                    } else {
                                        "Verbindung fehlgeschlagen: ${result.exceptionOrNull()?.message}"
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isTestingConnection
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Testen")
                            }
                        }
                    }
                }
            }

            // Target App Settings
            Text(
                text = "Ziel-App",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val selectedApp = installedApps.find { it.packageName == targetAppPackage }

                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAppPicker = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = selectedApp?.name ?: "Keine App ausgewählt",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (targetAppPackage.isNotBlank()) {
                                    Text(
                                        text = targetAppPackage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text("Ändern", color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Nur lesen wenn App inaktiv")
                            Text(
                                text = "Liest keine Daten während du die App benutzt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = onlyReadWhenInactive,
                            onCheckedChange = {
                                scope.launch {
                                    preferencesManager.setOnlyReadWhenAppInactive(it)
                                }
                            }
                        )
                    }

                    // Reading Interval
                    Text(
                        text = "Abruf-Intervall: $readingInterval Minuten",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Slider(
                        value = readingInterval.toFloat(),
                        onValueChange = { },
                        onValueChangeFinished = { },
                        valueRange = 1f..15f,
                        steps = 13,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(1, 5, 10, 15).forEach { interval ->
                            FilterChip(
                                selected = readingInterval == interval,
                                onClick = {
                                    scope.launch {
                                        preferencesManager.setReadingIntervalMinutes(interval)
                                    }
                                },
                                label = { Text("${interval}min") }
                            )
                        }
                    }
                }
            }

            // Display Settings
            Text(
                text = "Anzeige",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Glukose-Einheit", style = MaterialTheme.typography.bodyMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = glucoseUnit == GlucoseUnit.MG_DL,
                            onClick = {
                                scope.launch {
                                    preferencesManager.setGlucoseUnit(GlucoseUnit.MG_DL)
                                }
                            },
                            label = { Text("mg/dL") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = glucoseUnit == GlucoseUnit.MMOL_L,
                            onClick = {
                                scope.launch {
                                    preferencesManager.setGlucoseUnit(GlucoseUnit.MMOL_L)
                                }
                            },
                            label = { Text("mmol/L") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider()

                    Text("Grenzwerte (mg/dL)", style = MaterialTheme.typography.bodyMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = editLowThreshold,
                            onValueChange = {
                                editLowThreshold = it
                                it.toIntOrNull()?.let { value ->
                                    scope.launch {
                                        preferencesManager.setLowThreshold(value)
                                    }
                                }
                            },
                            label = { Text("Unterer") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = editHighThreshold,
                            onValueChange = {
                                editHighThreshold = it
                                it.toIntOrNull()?.let { value ->
                                    scope.launch {
                                        preferencesManager.setHighThreshold(value)
                                    }
                                }
                            },
                            label = { Text("Oberer") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // App Picker Dialog
    if (showAppPicker) {
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("Ziel-App auswählen") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(installedApps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        preferencesManager.setTargetAppPackage(app.packageName)
                                        DiabetesAccessibilityService.instance?.refreshTargetPackage()
                                    }
                                    showAppPicker = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}
