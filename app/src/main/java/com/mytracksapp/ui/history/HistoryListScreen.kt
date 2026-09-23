package com.mytracksapp.ui.history

import androidx.compose.foundation.clickable
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
    fun itemDuration(sessionId: String): String = "history_item_duration_$sessionId"
    fun itemStartDate(sessionId: String): String = "history_item_start_$sessionId"
}

/**
 * Past-sessions list (T10, UI-04): every finished session appears with its start date and trip
 * duration — the raw session id is intentionally not rendered (meaningless to a human scanning
 * their trips) and the raw sampling-interval count was replaced by duration per developer
 * feedback. Tapping a row calls [onSessionClick] with that session's id, which the caller wires
 * to navigation into [SessionDetailScreen].
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
                        .testTag(HistoryListScreenTestTags.item(item.sessionId))
                        .clickable { onSessionClick(item.sessionId) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = item.formattedStartDate,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.testTag(HistoryListScreenTestTags.itemStartDate(item.sessionId)),
                        )
                        Text(
                            text = "Duração: ${item.formattedDuration}",
                            modifier = Modifier.testTag(HistoryListScreenTestTags.itemDuration(item.sessionId)),
                        )
                    }
                }
            }
        }
    }
}
