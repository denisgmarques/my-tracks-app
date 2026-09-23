package com.mytracksapp.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytracksapp.ui.theme.Neutral700

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

    /** T14 (UI-01): the geocoded (or generic-placeholder) location name headline. */
    fun itemLocationName(sessionId: String): String = "history_item_location_name_$sessionId"

    /** T14 (UI-02): the unit-formatted total distance, part of the secondary info line. */
    fun itemDistance(sessionId: String): String = "history_item_distance_$sessionId"

    /** T14 (UI-03): the trailing chevron signaling the row is tappable. */
    fun itemArrow(sessionId: String): String = "history_item_arrow_$sessionId"
}

/**
 * Past-sessions list (T14, UI-01/UI-02/UI-03/UI-09): every finished session appears as an Organic
 * card — `design_handoff_my_tracks/my-tracks-design.html`'s "Histórico" section — with its
 * geocoded location name (or a generic placeholder, T11) as a 15px/700 headline, a 13px
 * [Neutral700] secondary line reading "`{data}` · Duração: `{duração}` · `{distância}`" (UI-02's
 * exact reading order), and a trailing chevron indicating the row is tappable (UI-03). The raw
 * session id is intentionally never rendered — meaningless to a human scanning their trips.
 * Tapping a row calls [onSessionClick] with that session's id, which the caller wires to
 * navigation into [SessionDetailScreen].
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
            .background(MaterialTheme.colorScheme.background)
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

        LazyColumn(
            modifier = Modifier.testTag(HistoryListScreenTestTags.LIST),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                                .clip(MaterialTheme.shapes.large)
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag(HistoryListScreenTestTags.item(item.sessionId))
                            .clickable { onSessionClick(item.sessionId) }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.formattedLocationName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag(
                                    HistoryListScreenTestTags.itemLocationName(item.sessionId),
                                ),
                            )
                            // UI-02's exact reading order: date, then duration, then distance, all
                            // on this one secondary line. Each piece keeps its own (pre-existing or
                            // new) test tag as a distinct Text node, joined visually by " · "
                            // separators that carry no semantics of their own.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.formattedStartDate,
                                    fontSize = 13.sp,
                                    color = Neutral700,
                                    modifier = Modifier.testTag(
                                        HistoryListScreenTestTags.itemStartDate(item.sessionId),
                                    ),
                                )
                                Text(text = " · ", fontSize = 13.sp, color = Neutral700)
                                Text(
                                    text = "Duração: ${item.formattedDuration}",
                                    fontSize = 13.sp,
                                    color = Neutral700,
                                    modifier = Modifier.testTag(
                                        HistoryListScreenTestTags.itemDuration(item.sessionId),
                                    ),
                                )
                                Text(text = " · ", fontSize = 13.sp, color = Neutral700)
                                Text(
                                    text = item.formattedDistance,
                                    fontSize = 13.sp,
                                    color = Neutral700,
                                    modifier = Modifier.testTag(
                                        HistoryListScreenTestTags.itemDistance(item.sessionId),
                                    ),
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Ver detalhes",
                            tint = Neutral700,
                            modifier = Modifier
                                .size(22.dp)
                                .testTag(HistoryListScreenTestTags.itemArrow(item.sessionId)),
                        )
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
