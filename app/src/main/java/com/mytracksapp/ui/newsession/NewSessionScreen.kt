package com.mytracksapp.ui.newsession

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mytracksapp.domain.model.SamplingInterval

/** Stable test tags for [NewSessionScreen], used by NewSessionScreenTest (T04). */
object NewSessionScreenTestTags {
    const val SCREEN = "new_session_screen"
    const val INTERVAL_OPTIONS_LIST = "interval_options_list"
    const val PERMISSION_DENIED_MESSAGE = "permission_denied_message"
    const val CONFIRM_BUTTON = "confirm_button"

    /** Tag for a single interval's selectable row, keyed by its second value (unique per T04). */
    fun intervalOption(interval: SamplingInterval): String = "interval_option_${interval.seconds}"
}

/**
 * New session screen (T04): presents the fixed set of [SamplingInterval] values as selectable options
 * (UI-01) — deliberately, there is no [androidx.compose.material3.TextField] or any other free
 * numeric/text input anywhere on this screen for the interval; the only way to pick a value is by
 * selecting one of the rendered [RadioButton] options, each bound to a [SamplingInterval] enum
 * member.
 */
@Composable
fun NewSessionScreen(
    viewModel: NewSessionViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(NewSessionScreenTestTags.SCREEN),
    ) {
        Text(
            text = "Selecione o intervalo de amostragem",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .selectableGroup()
                .testTag(NewSessionScreenTestTags.INTERVAL_OPTIONS_LIST),
        ) {
            uiState.availableIntervals.forEach { interval ->
                val isSelected = interval == uiState.selectedInterval
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            onClick = { viewModel.onIntervalSelected(interval) },
                            role = Role.RadioButton,
                        )
                        .testTag(NewSessionScreenTestTags.intervalOption(interval))
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${interval.seconds} s")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        uiState.permissionDeniedMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(NewSessionScreenTestTags.PERMISSION_DENIED_MESSAGE),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { viewModel.onConfirm() },
            enabled = !uiState.isStarting,
            modifier = Modifier.testTag(NewSessionScreenTestTags.CONFIRM_BUTTON),
        ) {
            Text(text = if (uiState.isStarting) "Iniciando..." else "Iniciar sessão")
        }
    }
}
