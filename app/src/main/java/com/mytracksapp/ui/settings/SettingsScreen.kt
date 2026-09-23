package com.mytracksapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.model.GpsPrecision
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit
import com.mytracksapp.ui.theme.Accent2
import com.mytracksapp.ui.theme.ColorBg
import com.mytracksapp.ui.theme.ColorDivider
import com.mytracksapp.ui.theme.Neutral300
import com.mytracksapp.ui.theme.Neutral600
import com.mytracksapp.ui.theme.Neutral700
import com.mytracksapp.ui.theme.PillShape

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

    /** T15 (UI-04): GPS-precision segmented control. */
    const val GPS_PRECISION_OPTIONS = "settings_gps_precision_options"

    /** T15 (UI-05): "Manter tela ativa durante a sessão" pill switch. */
    const val KEEP_SCREEN_ON_SWITCH = "settings_keep_screen_on_switch"

    /** T15 (UI-07): row showing/opening the default-export-format picker. */
    const val EXPORT_FORMAT_ROW = "settings_export_format_row"
    const val EXPORT_FORMAT_VALUE = "settings_export_format_value"
    const val EXPORT_FORMAT_MENU = "settings_export_format_menu"

    /** T15 (UI-08): destructive "Limpar histórico de trilhas" action + its confirmation dialog. */
    const val CLEAR_HISTORY_ACTION = "settings_clear_history_action"
    const val CLEAR_HISTORY_CONFIRM_DIALOG = "settings_clear_history_confirm_dialog"
    const val CLEAR_HISTORY_CONFIRM_BUTTON = "settings_clear_history_confirm_button"
    const val CLEAR_HISTORY_CANCEL_BUTTON = "settings_clear_history_cancel_button"

    fun intervalOption(interval: SamplingInterval): String = "settings_interval_option_${interval.seconds}"
    fun speedUnitOption(unit: SpeedUnit): String = "settings_speed_unit_option_${unit.name}"
    fun distanceUnitOption(unit: DistanceUnit): String = "settings_distance_unit_option_${unit.name}"
    fun gpsPrecisionOption(precision: GpsPrecision): String = "settings_gps_precision_option_${precision.name}"
    fun exportFormatOption(format: ExportFormat): String = "settings_export_format_option_${format.name}"
}

/** UI-04's two human-readable labels for [GpsPrecision]. */
private fun GpsPrecision.displayLabel(): String = when (this) {
    GpsPrecision.HIGH_ACCURACY -> "Alta (mais bateria)"
    GpsPrecision.BALANCED -> "Equilibrada"
}

/**
 * Settings screen — Organic redesign (T15, UI-09) of the single place that owns every
 * user-configurable value backed by [com.mytracksapp.data.settings.SettingsRepository]: sampling
 * interval (all 10 [SamplingInterval] values, per UI-09's explicit "not the mockup's 5-value
 * subset" requirement), speed/distance display units, the stop-detection radius/duration used by
 * [com.mytracksapp.domain.stats.SegmentClassifier], plus 4 new controls added by this task: GPS
 * precision (UI-04), keep-screen-on (UI-05), default export format (UI-07) and a destructive
 * "clear history" action gated by a mandatory confirmation dialog (UI-08, mirroring
 * [com.mytracksapp.ui.history.HistoryListScreenTestTags.DELETE_CONFIRM_DIALOG]'s pattern).
 *
 * The enum-backed selectors (including the 4 new controls) persist immediately on tap (no "Save"
 * button — conventional save-as-you-go settings UX). The two numeric fields persist on
 * Done/focus-loss instead, so a user is never fighting an immediate re-format or validation error
 * mid-keystroke — this behavior is unchanged from before this task.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    var exportFormatMenuExpanded by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag(SettingsScreenTestTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(text = "Configurações", style = MaterialTheme.typography.titleLarge)

        SettingsSection(label = "Intervalo de leitura do GPS") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface)
                    .selectableGroup()
                    .padding(horizontal = 8.dp)
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
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${interval.seconds} s", fontSize = 15.sp)
                    }
                }
            }
        }

        SettingsSection(label = "Unidade de velocidade") {
            SegmentedControl(
                options = SpeedUnit.entries,
                selectedOption = uiState.speedUnit,
                optionLabel = { it.displaySuffix },
                optionTestTag = SettingsScreenTestTags::speedUnitOption,
                onOptionSelected = viewModel::onSpeedUnitSelected,
                modifier = Modifier.testTag(SettingsScreenTestTags.SPEED_UNIT_OPTIONS),
            )
        }

        SettingsSection(label = "Unidade de distância") {
            SegmentedControl(
                options = DistanceUnit.entries,
                selectedOption = uiState.distanceUnit,
                optionLabel = { it.displaySuffix },
                optionTestTag = SettingsScreenTestTags::distanceUnitOption,
                onOptionSelected = viewModel::onDistanceUnitSelected,
                modifier = Modifier.testTag(SettingsScreenTestTags.DISTANCE_UNIT_OPTIONS),
            )
        }

        SettingsSection(label = "Raio de parada (metros)") {
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
                shape = MaterialTheme.shapes.medium,
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
        }

        SettingsSection(label = "Duração de parada (minutos)") {
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
                shape = MaterialTheme.shapes.medium,
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

        // --- T15's 4 new controls, in the mockup's order ---

        SettingsSection(label = "Precisão do GPS") {
            SegmentedControl(
                options = GpsPrecision.entries,
                selectedOption = uiState.gpsPrecision,
                optionLabel = { it.displayLabel() },
                optionTestTag = SettingsScreenTestTags::gpsPrecisionOption,
                onOptionSelected = viewModel::onGpsPrecisionSelected,
                modifier = Modifier.testTag(SettingsScreenTestTags.GPS_PRECISION_OPTIONS),
            )
        }

        SettingsCard {
            SettingsCardRow {
                Text(
                    text = "Manter tela ativa durante a sessão",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                PillSwitch(
                    checked = uiState.keepScreenOnEnabled,
                    onCheckedChange = viewModel::onKeepScreenOnToggled,
                    modifier = Modifier.testTag(SettingsScreenTestTags.KEEP_SCREEN_ON_SWITCH),
                )
            }
        }

        SettingsCard {
            SettingsCardRow(
                modifier = Modifier
                    .clickable { exportFormatMenuExpanded = true }
                    .testTag(SettingsScreenTestTags.EXPORT_FORMAT_ROW),
            ) {
                Text(
                    text = "Formato de exportação",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = uiState.defaultExportFormat.name,
                            fontSize = 13.sp,
                            color = Neutral700,
                            modifier = Modifier.testTag(SettingsScreenTestTags.EXPORT_FORMAT_VALUE),
                        )
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = Neutral600,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = exportFormatMenuExpanded,
                        onDismissRequest = { exportFormatMenuExpanded = false },
                        modifier = Modifier.testTag(SettingsScreenTestTags.EXPORT_FORMAT_MENU),
                    ) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.name) },
                                onClick = {
                                    viewModel.onDefaultExportFormatSelected(format)
                                    exportFormatMenuExpanded = false
                                },
                                modifier = Modifier.testTag(SettingsScreenTestTags.exportFormatOption(format)),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Neutral300)

            SettingsCardRow(
                modifier = Modifier
                    .clickable { showClearHistoryDialog = true }
                    .testTag(SettingsScreenTestTags.CLEAR_HISTORY_ACTION),
            ) {
                Text(
                    text = "Limpar histórico de trilhas",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            text = "My Tracks · versão 2.4.1",
            fontSize = 13.sp,
            color = Neutral600,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }

    // UI-08: "Limpar histórico" always shows this confirmation first; only its own confirm button
    // ever calls viewModel.clearHistory() — never the tap on CLEAR_HISTORY_ACTION itself. Mirrors
    // HistoryListScreenTestTags.DELETE_CONFIRM_DIALOG's exact visual/interaction pattern.
    if (showClearHistoryDialog) {
        AlertDialog(
            modifier = Modifier.testTag(SettingsScreenTestTags.CLEAR_HISTORY_CONFIRM_DIALOG),
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Limpar histórico de trilhas?") },
            text = {
                Text(
                    "Todas as sessões e todos os pontos GPS registrados serão apagados " +
                        "permanentemente. Essa ação não pode ser desfeita.",
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(SettingsScreenTestTags.CLEAR_HISTORY_CONFIRM_BUTTON),
                    onClick = {
                        viewModel.clearHistory()
                        showClearHistoryDialog = false
                    },
                ) {
                    Text("Limpar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag(SettingsScreenTestTags.CLEAR_HISTORY_CANCEL_BUTTON),
                    onClick = { showClearHistoryDialog = false },
                ) {
                    Text("Cancelar")
                }
            },
        )
    }
}

/** UI-09: uppercase 13px/700 [Neutral600] section label, matching every section in the mockup. */
@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = Neutral600,
        )
        content()
    }
}

/** The mockup's `.card` container: [ColorSurface]-adjacent surface, `radius-lg`, no internal padding (rows add their own). */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
private fun SettingsCardRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * UI-04/UI-09's `.seg`/`.seg-opt` segmented control: a pill-shaped row of equally-weighted
 * options, the selected one filled with [MaterialTheme.colorScheme.primary] (`--color-accent`).
 * Generic over [T] so it backs GPS precision, speed unit and distance unit alike.
 */
@Composable
private fun <T> SegmentedControl(
    options: List<T>,
    selectedOption: T,
    optionLabel: (T) -> String,
    optionTestTag: (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, ColorDivider, PillShape)
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selectedOption
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(PillShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        onClick = { onOptionSelected(option) },
                        role = Role.RadioButton,
                    )
                    .testTag(optionTestTag(option))
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = optionLabel(option),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * UI-05/UI-09's pill switch, matching `my-tracks-design.html`'s exact spec: a 44×26dp pill track
 * ([Accent2] when on, [Neutral300] when off) with a 20dp circular dot that slides between the
 * track's start (off) and end (on) edges, each inset 3dp.
 */
@Composable
private fun PillSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val trackColor = if (checked) Accent2 else Neutral300
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(PillShape)
            .background(trackColor)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(ColorBg),
        )
    }
}

