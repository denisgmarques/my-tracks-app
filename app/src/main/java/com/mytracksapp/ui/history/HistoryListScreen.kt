package com.mytracksapp.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Stable test tags for [HistoryListScreen], used by history UI tests. */
object HistoryListScreenTestTags {
    const val SCREEN = "history_list_screen"
    const val LIST = "history_list"
    const val EMPTY_MESSAGE = "history_empty_message"
    const val DELETE_CONFIRM_DIALOG = "history_delete_confirm_dialog"
    const val DELETE_CONFIRM_BUTTON = "history_delete_confirm_button"
    const val DELETE_CANCEL_BUTTON = "history_delete_cancel_button"

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
 *
 * Swiping a row left (only left — right-swipe is disabled so an accidental swipe in the other
 * direction never triggers anything) reveals a delete action. The swipe itself never deletes
 * anything: [androidx.compose.material3.SwipeToDismissBoxState]'s `confirmValueChange` always
 * returns `false`, which snaps the row back and instead opens a confirmation dialog. Only
 * confirming that dialog actually calls [HistoryViewModel.deleteSession].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryListScreen(
    viewModel: HistoryViewModel,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

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
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            pendingDeleteId = item.sessionId
                        }
                        // Always false: the swipe itself never removes the row. Only the
                        // confirmation dialog's own "Excluir" button does, via deleteSession().
                        false
                    },
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Excluir",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(end = 24.dp),
                            )
                        }
                    },
                ) {
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

    val deleteTargetId = pendingDeleteId
    if (deleteTargetId != null) {
        AlertDialog(
            modifier = Modifier.testTag(HistoryListScreenTestTags.DELETE_CONFIRM_DIALOG),
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Excluir sessão?") },
            text = { Text("Essa sessão e todos os seus pontos GPS serão apagados permanentemente.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(HistoryListScreenTestTags.DELETE_CONFIRM_BUTTON),
                    onClick = {
                        viewModel.deleteSession(deleteTargetId)
                        pendingDeleteId = null
                    },
                ) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag(HistoryListScreenTestTags.DELETE_CANCEL_BUTTON),
                    onClick = { pendingDeleteId = null },
                ) {
                    Text("Cancelar")
                }
            },
        )
    }
}
