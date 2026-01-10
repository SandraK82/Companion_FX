package com.diabetesscreenreader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val NIGHTSCOUT_URL = stringPreferencesKey("nightscout_url")
        val NIGHTSCOUT_API_SECRET = stringPreferencesKey("nightscout_api_secret")
        val NIGHTSCOUT_ENABLED = booleanPreferencesKey("nightscout_enabled")
        val TARGET_APP_PACKAGE = stringPreferencesKey("target_app_package")
        val READING_INTERVAL_MINUTES = intPreferencesKey("reading_interval_minutes")
        val GLUCOSE_UNIT = stringPreferencesKey("glucose_unit")
        val LOW_THRESHOLD = intPreferencesKey("low_threshold")
        val HIGH_THRESHOLD = intPreferencesKey("high_threshold")
        val SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        val ONLY_READ_WHEN_APP_INACTIVE = booleanPreferencesKey("only_read_when_app_inactive")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
    }

    // Nightscout Settings
    val nightscoutUrl: Flow<String> = context.dataStore.data.map { it[Keys.NIGHTSCOUT_URL] ?: "" }
    val nightscoutApiSecret: Flow<String> = context.dataStore.data.map { it[Keys.NIGHTSCOUT_API_SECRET] ?: "" }
    val nightscoutEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.NIGHTSCOUT_ENABLED] ?: false }

    suspend fun setNightscoutUrl(url: String) {
        context.dataStore.edit { it[Keys.NIGHTSCOUT_URL] = url }
    }

    suspend fun setNightscoutApiSecret(secret: String) {
        context.dataStore.edit { it[Keys.NIGHTSCOUT_API_SECRET] = secret }
    }

    suspend fun setNightscoutEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NIGHTSCOUT_ENABLED] = enabled }
    }

    // Target App Settings
    val targetAppPackage: Flow<String> = context.dataStore.data.map {
        it[Keys.TARGET_APP_PACKAGE] ?: ""
    }

    suspend fun setTargetAppPackage(packageName: String) {
        context.dataStore.edit { it[Keys.TARGET_APP_PACKAGE] = packageName }
    }

    // Reading Interval
    val readingIntervalMinutes: Flow<Int> = context.dataStore.data.map {
        it[Keys.READING_INTERVAL_MINUTES] ?: 5
    }

    suspend fun setReadingIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.READING_INTERVAL_MINUTES] = minutes }
    }

    // Glucose Display Settings
    val glucoseUnit: Flow<GlucoseUnit> = context.dataStore.data.map { prefs ->
        prefs[Keys.GLUCOSE_UNIT]?.let { GlucoseUnit.valueOf(it) } ?: GlucoseUnit.MG_DL
    }

    suspend fun setGlucoseUnit(unit: GlucoseUnit) {
        context.dataStore.edit { it[Keys.GLUCOSE_UNIT] = unit.name }
    }

    val lowThreshold: Flow<Int> = context.dataStore.data.map { it[Keys.LOW_THRESHOLD] ?: 70 }
    val highThreshold: Flow<Int> = context.dataStore.data.map { it[Keys.HIGH_THRESHOLD] ?: 180 }

    suspend fun setLowThreshold(value: Int) {
        context.dataStore.edit { it[Keys.LOW_THRESHOLD] = value }
    }

    suspend fun setHighThreshold(value: Int) {
        context.dataStore.edit { it[Keys.HIGH_THRESHOLD] = value }
    }

    // Service Settings
    val serviceEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.SERVICE_ENABLED] ?: false
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SERVICE_ENABLED] = enabled }
    }

    val onlyReadWhenAppInactive: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ONLY_READ_WHEN_APP_INACTIVE] ?: true
    }

    suspend fun setOnlyReadWhenAppInactive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ONLY_READ_WHEN_APP_INACTIVE] = enabled }
    }

    // Sync Time
    val lastSyncTime: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_SYNC_TIME] ?: 0L }

    suspend fun setLastSyncTime(time: Long) {
        context.dataStore.edit { it[Keys.LAST_SYNC_TIME] = time }
    }

    // Convenience methods for synchronous access
    suspend fun getNightscoutUrlSync(): String = nightscoutUrl.first()
    suspend fun getNightscoutApiSecretSync(): String = nightscoutApiSecret.first()
    suspend fun getNightscoutEnabledSync(): Boolean = nightscoutEnabled.first()
    suspend fun getTargetAppPackageSync(): String = targetAppPackage.first()
    suspend fun getGlucoseUnitSync(): GlucoseUnit = glucoseUnit.first()
    suspend fun getLowThresholdSync(): Int = lowThreshold.first()
    suspend fun getHighThresholdSync(): Int = highThreshold.first()
}
