package com.mytracksapp.domain.export

import com.mytracksapp.data.local.entity.GpsPointEntity
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T11 — [CsvExporter] validation against the real `csv-export-schema.json` file (CT-02): field
 * names/order, `session_id`'s `format: uuid`, and `segment_status`'s closed enum are all read
 * directly out of the schema file (not duplicated by hand) and checked against generated output.
 */
class CsvExporterTest {

    private fun findSpecFile(fileName: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, ".spec/features/gps-tracking-prototype/$fileName")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error(
            "Could not locate $fileName under any ancestor .spec/features/gps-tracking-prototype " +
                "directory (searched upward from ${File(".").absoluteFile})",
        )
    }

    // --- Minimal JSON field extraction, reading the actual schema file's "fields" array. ---

    private fun extractFieldsArrayContent(json: String): String {
        val keyIndex = json.indexOf("\"fields\"")
        require(keyIndex >= 0) { "schema JSON has no \"fields\" key" }
        val arrayStart = json.indexOf('[', keyIndex)
        var depth = 0
        var i = arrayStart
        while (i < json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(arrayStart + 1, i)
                }
            }
            i++
        }
        error("Unbalanced \"fields\" array in schema JSON")
    }

    private fun splitTopLevelJsonObjects(arrayContent: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in arrayContent.indices) {
            when (arrayContent[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects += arrayContent.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun extractStringValue(objectJson: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(objectJson)?.groupValues?.get(1)

    private fun extractEnumValues(objectJson: String): List<String>? {
        val match = Regex("\"enum\"\\s*:\\s*\\[([^\\]]*)\\]").find(objectJson) ?: return null
        return Regex("\"([^\"]*)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    private lateinit var fieldObjects: List<String>
    private lateinit var fieldNames: List<String>
    private lateinit var allowedSegmentStatuses: List<String>

    @Before
    fun loadSchema() {
        val schemaJson = findSpecFile("csv-export-schema.json").readText()
        fieldObjects = splitTopLevelJsonObjects(extractFieldsArrayContent(schemaJson))
        fieldNames = fieldObjects.mapNotNull { extractStringValue(it, "name") }
        val segmentStatusField = fieldObjects.first { extractStringValue(it, "name") == "segment_status" }
        allowedSegmentStatuses = extractEnumValues(segmentStatusField)
            ?: error("csv-export-schema.json's segment_status field has no enum constraint")
    }

    private fun points(n: Int): List<GpsPointEntity> {
        val sessionId = "session-x"
        return (0 until n).map { i ->
            GpsPointEntity(
                sessionId = sessionId,
                timestamp = 1_700_000_000_000L + i * 15_000L,
                latitude = -23.5505 + i * 0.001,
                longitude = -46.6333 + i * 0.001,
                accuracy = 5f + i,
            )
        }
    }

    @Test
    fun `schema declares exactly the 7 CT-02 fields in the specified order`() {
        assertEquals(
            listOf("session_id", "timestamp", "latitude", "longitude", "accuracy", "speed_instant", "segment_status"),
            fieldNames,
        )
    }

    @Test
    fun `session_id field is declared as format uuid in the schema`() {
        val sessionIdField = fieldObjects.first { extractStringValue(it, "name") == "session_id" }
        assertEquals("uuid", extractStringValue(sessionIdField, "format"))
    }

    @Test
    fun `segment_status enum in the schema is exactly moving and stopped`() {
        assertEquals(listOf("moving", "stopped"), allowedSegmentStatuses)
    }

    @Test
    fun `generated CSV header matches the schema field order exactly`() {
        val sessionId = UUID.randomUUID().toString()
        val csv = CsvExporter.export(sessionId, points(3))

        val header = csv.lineSequence().first().split(",")
        assertEquals(fieldNames, header)
    }

    @Test
    fun `generated CSV has exactly one row per point, with a UUID session_id and enum-restricted segment_status`() {
        val sessionId = UUID.randomUUID().toString()
        val pts = points(4)
        val csv = CsvExporter.export(sessionId, pts)

        val lines = csv.trim().lines()
        val dataLines = lines.drop(1) // drop header
        assertEquals(pts.size, dataLines.size)

        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        dataLines.forEach { line ->
            val columns = line.split(",")
            assertEquals(7, columns.size)

            val (rowSessionId, _, latitude, longitude, accuracy, speedInstant, segmentStatus) = columns

            assertTrue("session_id must be a UUID string: $rowSessionId", uuidRegex.matches(rowSessionId))
            assertEquals(sessionId, rowSessionId)
            assertTrue(latitude.toDouble() in -90.0..90.0)
            assertTrue(longitude.toDouble() in -180.0..180.0)
            assertTrue(accuracy.toDouble() >= 0.0)
            assertTrue(speedInstant.toDouble() >= 0.0)
            assertTrue(
                "segment_status must be one of $allowedSegmentStatuses, was $segmentStatus",
                allowedSegmentStatuses.contains(segmentStatus),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `exporting a session with no collected points is rejected`() {
        CsvExporter.export(UUID.randomUUID().toString(), emptyList())
    }

    private operator fun <T> List<T>.component6(): T = this[5]
    private operator fun <T> List<T>.component7(): T = this[6]
}
