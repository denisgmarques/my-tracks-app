package com.mytracksapp.ui.newsession

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Stable test tags for [NewSessionScreen], used by NewSessionScreenTest. */
object NewSessionScreenTestTags {
    const val SCREEN = "new_session_screen"
    const val CONFIGURED_INTERVAL_TEXT = "configured_interval_text"
    const val PERMISSION_DENIED_MESSAGE = "permission_denied_message"
    const val CONFIRM_BUTTON = "confirm_button"
}

/**
 * New session screen (follow-up phase — simplified): the per-session sampling-interval picker
 * (UI-01) that originally lived here was removed. Settings now owns the interval exclusively (see
 * `com.mytracksapp.ui.settings.SettingsScreen`) — this screen only displays the currently
 * configured value read-only, with a hint pointing the user at Settings to change it, plus the
 * single "Iniciar sessão" action (still gated by the RF-03 permission check in
 * [NewSessionViewModel.onConfirm]).
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
            text = "Nova sessão",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Intervalo configurado: ${uiState.configuredInterval.seconds} s",
            modifier = Modifier.testTag(NewSessionScreenTestTags.CONFIGURED_INTERVAL_TEXT),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Para alterar o intervalo, acesse Configurações.",
            style = MaterialTheme.typography.bodySmall,
        )

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
