package com.mytracksapp.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The full bundle of user-configurable app settings (follow-up phase, post-prototype developer
 * feedback — not part of the original PLAN.md). Defaults here are exactly what [SettingsRepository]
 * must produce when nothing has ever been persisted.
 */
data class UserSettings(
    val samplingInterval: SamplingInterval = SamplingInterval.ONE_SECOND,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val distanceUnit: DistanceUnit = DistanceUnit.KM,
    val stopRadiusMeters: Double = SegmentClassifier.STOP_RADIUS_METERS,
    val stopDurationMillis: Long = SegmentClassifier.STOP_DURATION_MILLIS,
)

/** Preference keys, kept private so [UserSettings] (typed, enum-based) is the only public surface. */
private object Keys {
    val SAMPLING_INTERVAL_SECONDS = intPreferencesKey("sampling_interval_seconds")
    val SPEED_UNIT = stringPreferencesKey("speed_unit")
    val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
    val STOP_RADIUS_METERS = doublePreferencesKey("stop_radius_meters")
    val STOP_DURATION_MILLIS = longPreferencesKey("stop_duration_millis")
}

/**
 * Settings persistence backed by Jetpack DataStore Preferences (follow-up phase — this is the
 * first settings/preferences persistence layer in the codebase; no DataStore or SharedPreferences
 * existed before).
 *
 * Follows this codebase's existing conventions: no DI framework anywhere, plain constructor
 * injection, a `Context`-taking class meant to be constructed once at the composition root
 * ([com.mytracksapp.MainActivity] / `AppNavigation.kt`) exactly the way
 * `AppDatabase.getInstance(context)` is wired in today — a later phase (Phase B) does that wiring;
 * this phase only builds and tests the repository in isolation.
 *
 * The underlying [DataStore] is memoized per [dataStoreName] (mirroring `AppDatabase.getInstance`'s
 * singleton-per-process pattern), so constructing multiple [SettingsRepository] instances for the
 * same file — e.g. from different screens, or simulating an app restart in tests — never creates
 * two competing DataStore instances pointed at the same file, which Jetpack DataStore does not
 * support safely. Passing a distinct [dataStoreName] (as instrumented tests do) gives full
 * isolation between call sites without any of them stepping on each other's persisted state.
 */
class SettingsRepository(
    context: Context,
    dataStoreName: String = DEFAULT_DATASTORE_NAME,
) {

    private val dataStore: DataStore<Preferences> = dataStoreFor(context.applicationContext, dataStoreName)

    /** Emits the current [UserSettings] immediately and again on every persisted change. */
    val userSettings: Flow<UserSettings> = dataStore.data.map { preferences -> preferences.toUserSettings() }

    suspend fun setSamplingInterval(interval: SamplingInterval) {
        dataStore.edit { it[Keys.SAMPLING_INTERVAL_SECONDS] = interval.seconds }
    }

    suspend fun setSpeedUnit(unit: SpeedUnit) {
        dataStore.edit { it[Keys.SPEED_UNIT] = unit.name }
    }

    suspend fun setDistanceUnit(unit: DistanceUnit) {
        dataStore.edit { it[Keys.DISTANCE_UNIT] = unit.name }
    }

    suspend fun setStopRadiusMeters(meters: Double) {
        dataStore.edit { it[Keys.STOP_RADIUS_METERS] = meters }
    }

    suspend fun setStopDurationMillis(millis: Long) {
        dataStore.edit { it[Keys.STOP_DURATION_MILLIS] = millis }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        val defaults = UserSettings()
        val samplingIntervalSeconds = this[Keys.SAMPLING_INTERVAL_SECONDS]
        val samplingInterval = samplingIntervalSeconds
            ?.let { seconds -> SamplingInterval.entries.firstOrNull { it.seconds == seconds } }
            ?: defaults.samplingInterval

        val speedUnit = this[Keys.SPEED_UNIT]
            ?.let { name -> runCatching { SpeedUnit.valueOf(name) }.getOrNull() }
            ?: defaults.speedUnit

        val distanceUnit = this[Keys.DISTANCE_UNIT]
            ?.let { name -> runCatching { DistanceUnit.valueOf(name) }.getOrNull() }
            ?: defaults.distanceUnit

        return UserSettings(
            samplingInterval = samplingInterval,
            speedUnit = speedUnit,
            distanceUnit = distanceUnit,
            stopRadiusMeters = this[Keys.STOP_RADIUS_METERS] ?: defaults.stopRadiusMeters,
            stopDurationMillis = this[Keys.STOP_DURATION_MILLIS] ?: defaults.stopDurationMillis,
        )
    }

    companion object {
        const val DEFAULT_DATASTORE_NAME = "user_settings"

        private val instancesLock = Any()
        private val instances = mutableMapOf<String, DataStore<Preferences>>()

        /** Memoized per (application context, file name) so a file only ever has one active DataStore. */
        private fun dataStoreFor(appContext: Context, name: String): DataStore<Preferences> =
            synchronized(instancesLock) {
                instances.getOrPut(name) {
                    PreferenceDataStoreFactory.create(
                        produceFile = { appContext.preferencesDataStoreFile(name) },
                    )
                }
            }
    }
}
