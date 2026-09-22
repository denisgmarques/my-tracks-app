package com.mytracksapp.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.mytracksapp.domain.export.ExportFormat

/** Stable test tags for [ExportFormatDialog], used by ExportFormatDialogTest (UI-05, RF-08). */
object ExportFormatDialogTestTags {
    const val DIALOG = "export_format_dialog"
    const val GPX_OPTION = "export_format_gpx_option"
    const val CSV_OPTION = "export_format_csv_option"
    const val CANCEL = "export_format_cancel"
}

/**
 * T12 — GPX/CSV format picker shown when the user triggers export from a finished session's
 * detail screen ([com.mytracksapp.ui.history.SessionDetailScreen], UI-05).
 *
 * Presents exactly the two MVP-mandatory formats (RF-08: "Ambos os formatos ... são obrigatórios
 * no MVP"). Picking either invokes [onFormatSelected] with the corresponding [ExportFormat] — the
 * caller is responsible for actually driving `ExportService.export(sessionId, format)` and
 * dismissing the dialog.
 */
@Composable
fun ExportFormatDialog(
    onDismissRequest: () -> Unit,
    onFormatSelected: (ExportFormat) -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(ExportFormatDialogTestTags.DIALOG),
        onDismissRequest = onDismissRequest,
        title = { Text("Exportar sessão") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Escolha o formato de exportação:")
                TextButton(
                    onClick = { onFormatSelected(ExportFormat.GPX) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ExportFormatDialogTestTags.GPX_OPTION),
                ) {
                    Text("GPX")
                }
                TextButton(
                    onClick = { onFormatSelected(ExportFormat.CSV) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ExportFormatDialogTestTags.CSV_OPTION),
                ) {
                    Text("CSV")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.testTag(ExportFormatDialogTestTags.CANCEL),
            ) {
                Text("Cancelar")
            }
        },
    )
}
