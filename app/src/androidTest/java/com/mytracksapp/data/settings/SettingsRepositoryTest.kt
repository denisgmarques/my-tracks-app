package com.mytracksapp.data.settings

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for [SettingsRepository] (follow-up phase — first settings persistence layer
 * in this codebase). Follows [com.mytracksapp.data.local.TrackingSessionDaoTest]'s pattern: real
 * Android context via [ApplicationProvider], `runBlocking` for suspend calls, cleanup in
 * `@After`. Each test uses a uniquely-named DataStore file (a fresh [UUID] per test) so tests
 * never see each other's persisted state, since DataStore files persist on disk across test
 * methods within the same instrumented test process.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun repository() = SettingsRepository(context, dataStoreName)

    @Test
    fun defaultsAreAsSpecified_whenNothingHasBeenSet() = runBlocking {
        val settings = repository().userSettings.first()

        assertEquals(SamplingInterval.ONE_SECOND, settings.samplingInterval)
        assertEquals(SpeedUnit.KMH, settings.speedUnit)
        assertEquals(DistanceUnit.KM, settings.distanceUnit)
        assertEquals(SegmentClassifier.STOP_RADIUS_METERS, settings.stopRadiusMeters, 0.0)
        assertEquals(SegmentClassifier.STOP_DURATION_MILLIS, settings.stopDurationMillis)
    }

    @Test
    fun setSamplingInterval_persistsAndFlowReEmits() = runBlocking {
        val repository = repository()

        repository.setSamplingInterval(SamplingInterval.SIXTY_SECONDS)

        assertEquals(SamplingInterval.SIXTY_SECONDS, repository.userSettings.first().samplingInterval)
    }

    @Test
    fun setSpeedUnit_persistsAndFlowReEmits() = runBlocking {
        val repository = repository()

        repository.setSpeedUnit(SpeedUnit.KNOTS)

        assertEquals(SpeedUnit.KNOTS, repository.userSettings.first().speedUnit)
    }

    @Test
    fun setDistanceUnit_persistsAndFlowReEmits() = runBlocking {
        val repository = repository()

        repository.setDistanceUnit(DistanceUnit.MILES)

        assertEquals(DistanceUnit.MILES, repository.userSettings.first().distanceUnit)
    }

    @Test
    fun setStopRadiusMeters_persistsAndFlowReEmits() = runBlocking {
        val repository = repository()

        repository.setStopRadiusMeters(75.5)

        assertEquals(75.5, repository.userSettings.first().stopRadiusMeters, 0.0001)
    }

    @Test
    fun setStopDurationMillis_persistsAndFlowReEmits() = runBlocking {
        val repository = repository()

        repository.setStopDurationMillis(120_000L)

        assertEquals(120_000L, repository.userSettings.first().stopDurationMillis)
    }

    @Test
    fun valuesSurviveAcrossRepositoryInstances_simulatingAppRestart() = runBlocking {
        val firstInstance = repository()
        firstInstance.setSamplingInterval(SamplingInterval.THIRTY_SECONDS)
        firstInstance.setSpeedUnit(SpeedUnit.MS)
        firstInstance.setDistanceUnit(DistanceUnit.NAUTICAL_MILES)
        firstInstance.setStopRadiusMeters(200.0)
        firstInstance.setStopDurationMillis(600_000L)

        // A brand-new SettingsRepository object, backed by the same named DataStore file — this
        // simulates the app process restarting and MainActivity constructing a fresh repository.
        val secondInstance = repository()
        val restored = secondInstance.userSettings.first()

        assertEquals(SamplingInterval.THIRTY_SECONDS, restored.samplingInterval)
        assertEquals(SpeedUnit.MS, restored.speedUnit)
        assertEquals(DistanceUnit.NAUTICAL_MILES, restored.distanceUnit)
        assertEquals(200.0, restored.stopRadiusMeters, 0.0001)
        assertEquals(600_000L, restored.stopDurationMillis)
    }

    @Test
    fun settingOneFieldDoesNotAffectOthers() = runBlocking {
        val repository = repository()

        repository.setSamplingInterval(SamplingInterval.FIFTEEN_SECONDS)
        val afterFirstSet = repository.userSettings.first()
        assertEquals(SpeedUnit.KMH, afterFirstSet.speedUnit)
        assertEquals(DistanceUnit.KM, afterFirstSet.distanceUnit)

        repository.setSpeedUnit(SpeedUnit.KNOTS)
        val afterSecondSet = repository.userSettings.first()
        assertEquals(SamplingInterval.FIFTEEN_SECONDS, afterSecondSet.samplingInterval)
        assertEquals(SpeedUnit.KNOTS, afterSecondSet.speedUnit)
        assertEquals(DistanceUnit.KM, afterSecondSet.distanceUnit)
    }
}
