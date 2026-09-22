package com.mytracksapp.domain.export

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import java.io.File
import kotlinx.coroutines.flow.first

/** The two export formats RF-08 mandates — both required in the MVP, chosen at export time. */
enum class ExportFormat { GPX, CSV }

/**
 * A generated export's content, ready to be written to disk (RF-08).
 *
 * Kept as an in-memory value (rather than eagerly writing a file) so [ExportService] stays
 * testable in plain JVM unit tests without any filesystem/Android dependency; [writeTo] is an
 * explicit, separate step for callers (e.g. T12's export UI) that need an actual file.
 */
data class ExportedFile(
    val fileName: String,
    val content: String,
    val format: ExportFormat,
) {
    /** Writes [content] to `<[directory]>/[fileName]`, creating [directory] if needed. */
    fun writeTo(directory: File): File {
        directory.mkdirs()
        val file = File(directory, fileName)
        file.writeText(content)
        return file
    }
}

/**
 * Generates GPX/CSV export files for a finished tracking session (RF-08, CT-01, CT-02, UI-05).
 *
 * Reads a session's persisted metadata and points from Room, then delegates the actual file
 * structure to [GpxExporter]/[CsvExporter]. Per RF-08/UI-05's AC ("ambos os formatos estão
 * disponíveis para qualquer sessão encerrada"), export is only ever allowed for a session with
 * [SessionStatus.FINISHED] — anything else is a programming error on the caller's part (T12's UI
 * is expected to only ever offer the export action for a finished session, per its own
 * acceptance criteria) and fails loudly rather than silently producing a bogus/partial file.
 */
class ExportService(
    private val trackingSessionDao: TrackingSessionDao,
    private val gpsPointDao: GpsPointDao,
) {

    /**
     * @throws NoSuchElementException if no session exists for [sessionId].
     * @throws IllegalStateException if the session is not [SessionStatus.FINISHED].
     */
    suspend fun export(sessionId: String, format: ExportFormat): ExportedFile {
        val session = trackingSessionDao.getSessionById(sessionId).first()
            ?: throw NoSuchElementException("No session found for id=$sessionId")
        check(session.status == SessionStatus.FINISHED) {
            "Session $sessionId is not finished (status=${session.status}); " +
                "export is only available for finished sessions (RF-08/UI-05)"
        }

        val points = gpsPointDao.getPointsForSession(sessionId).first()

        val content = when (format) {
            ExportFormat.GPX -> GpxExporter.export(points)
            ExportFormat.CSV -> CsvExporter.export(sessionId, points)
        }
        val extension = when (format) {
            ExportFormat.GPX -> "gpx"
            ExportFormat.CSV -> "csv"
        }

        return ExportedFile(fileName = "session-$sessionId.$extension", content = content, format = format)
    }
}
