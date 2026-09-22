package com.mytracksapp.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Stable test tags for [HistoryListScreen], used by history UI tests. */
object HistoryListScreenTestTags {
    const val SCREEN = "history_list_screen"
    const val LIST = "history_list"
    const val EMPTY_MESSAGE = "history_empty_message"

    fun item(sessionId: String): String = "history_item_$sessionId"
    fun itemInterval(sessionId: String): String = "history_item_interval_$sessionId"
    fun itemStartDateTime(sessionId: String): String = "history_item_start_$sessionId"
}

/**
 * Past-sessions list (T10, UI-04): every finished session appears with its id, start date/time
 * and configured sampling interval — the 3 fields UI-04 requires at minimum. Tapping a row is
 * expected to navigate to [SessionDetailScreen] for that session id (wiring left to the caller's
 * navigation setup).
 */
@Composable
fun HistoryListScreen(
    viewModel: HistoryViewModel,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HistoryListScreenTestTags.SCREEN),
    ) {
        if (uiState.sessions.isEmpty()) {
            Text(
                text = "Nenhuma sessão encerrada ainda",
                modifier = Modifier
                    .padding(16.dp)
                    .testTag(HistoryListScreenTestTags.EMPTY_MESSAGE),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.testTag(HistoryListScreenTestTags.LIST)) {
            items(uiState.sessions, key = { it.sessionId }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .testTag(HistoryListScreenTestTags.item(item.sessionId)),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = item.sessionId, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = item.formattedStartDateTime,
                            modifier = Modifier.testTag(HistoryListScreenTestTags.itemStartDateTime(item.sessionId)),
                        )
                        Text(
                            text = "Intervalo: ${item.samplingIntervalSeconds}s",
                            modifier = Modifier.testTag(HistoryListScreenTestTags.itemInterval(item.sessionId)),
                        )
                    }
                }
            }
        }
    }
}
