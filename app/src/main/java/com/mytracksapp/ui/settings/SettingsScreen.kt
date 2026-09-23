package com.mytracksapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit

/** Stable test tags for [SettingsScreen]. */
object SettingsScreenTestTags {
    const val SCREEN = "settings_screen"
    const val INTERVAL_OPTIONS_LIST = "settings_interval_options_list"
    const val SPEED_UNIT_OPTIONS = "settings_speed_unit_options"
    const val DISTANCE_UNIT_OPTIONS = "settings_distance_unit_options"
    const val STOP_RADIUS_FIELD = "settings_stop_radius_field"
    const val STOP_RADIUS_ERROR = "settings_stop_radius_error"
    const val STOP_DURATION_FIELD = "settings_stop_duration_field"
    const val STOP_DURATION_ERROR = "settings_stop_duration_error"

    fun intervalOption(interval: SamplingInterval): String = "settings_interval_option_${interval.seconds}"
    fun speedUnitOption(unit: SpeedUnit): String = "settings_speed_unit_option_${unit.name}"
    fun distanceUnitOption(unit: DistanceUnit): String = "settings_distance_unit_option_${unit.name}"
}

/**
 * Settings screen (follow-up phase, post-prototype developer feedback): the single place that
 * owns every user-configurable value backed by [com.mytracksapp.data.settings.SettingsRepository]
 * — sampling interval (moved here from the now-simplified `NewSessionScreen`), speed/distance
 * display units, and the stop-detection radius/duration used by
 * [com.mytracksapp.domain.stats.SegmentClassifier].
 *
 * The enum-backed selectors persist immediately on tap (no "Save" button — conventional
 * save-as-you-go settings UX). The two numeric fields persist on Done/focus-loss instead, so a
 * user is never fighting an immediate re-format or validation error mid-keystroke.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(SettingsScreenTestTags.SCREEN),
    ) {
        Text(text = "Configurações", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Intervalo de amostragem", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .selectableGroup()
                .testTag(SettingsScreenTestTags.INTERVAL_OPTIONS_LIST),
        ) {
            SamplingInterval.entries.forEach { interval ->
                val isSelected = interval == uiState.samplingInterval
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            onClick = { viewModel.onSamplingIntervalSelected(interval) },
                            role = Role.RadioButton,
                        )
                        .testTag(SettingsScreenTestTags.intervalOption(interval))
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${interval.seconds} s")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Unidade de velocidade", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .selectableGroup()
                .testTag(SettingsScreenTestTags.SPEED_UNIT_OPTIONS),
        ) {
            SpeedUnit.entries.forEach { unit ->
                val isSelected = unit == uiState.speedUnit
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = isSelected,
                            onClick = { viewModel.onSpeedUnitSelected(unit) },
                            role = Role.RadioButton,
                        )
                        .testTag(SettingsScreenTestTags.speedUnitOption(unit))
                        .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = unit.name)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Unidade de distância", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .selectableGroup()
                .testTag(SettingsScreenTestTags.DISTANCE_UNIT_OPTIONS),
        ) {
            DistanceUnit.entries.forEach { unit ->
                val isSelected = unit == uiState.distanceUnit
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = isSelected,
                            onClick = { viewModel.onDistanceUnitSelected(unit) },
                            role = Role.RadioButton,
                        )
                        .testTag(SettingsScreenTestTags.distanceUnitOption(unit))
                        .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = unit.name)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Raio de parada (metros)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        var stopRadiusWasFocused by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = uiState.stopRadiusMetersText,
            onValueChange = viewModel::onStopRadiusTextChanged,
            singleLine = true,
            isError = uiState.stopRadiusError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    viewModel.onStopRadiusCommit()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsScreenTestTags.STOP_RADIUS_FIELD)
                .onFocusChanged { focusState ->
                    if (stopRadiusWasFocused && !focusState.isFocused) {
                        viewModel.onStopRadiusCommit()
                    }
                    stopRadiusWasFocused = focusState.isFocused
                },
        )
        uiState.stopRadiusError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SettingsScreenTestTags.STOP_RADIUS_ERROR),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Duração de parada (minutos)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        var stopDurationWasFocused by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = uiState.stopDurationMinutesText,
            onValueChange = viewModel::onStopDurationTextChanged,
            singleLine = true,
            isError = uiState.stopDurationError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    viewModel.onStopDurationCommit()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsScreenTestTags.STOP_DURATION_FIELD)
                .onFocusChanged { focusState ->
                    if (stopDurationWasFocused && !focusState.isFocused) {
                        viewModel.onStopDurationCommit()
                    }
                    stopDurationWasFocused = focusState.isFocused
                },
        )
        uiState.stopDurationError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SettingsScreenTestTags.STOP_DURATION_ERROR),
            )
        }
    }
}
