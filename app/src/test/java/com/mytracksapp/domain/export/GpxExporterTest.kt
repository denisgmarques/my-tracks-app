package com.mytracksapp.domain.export

import com.mytracksapp.data.local.entity.GpsPointEntity
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T11 — [GpxExporter] structural validation against the real `gpx-export.xsd` file (CT-01),
 * using `javax.xml.validation` (JDK-standard XSD validator) — not a hand-rolled structural check.
 */
class GpxExporterTest {

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

    private fun points(n: Int): List<GpsPointEntity> = (0 until n).map { i ->
        GpsPointEntity(
            sessionId = "session-1",
            timestamp = 1_700_000_000_000L + i * 15_000L,
            latitude = -23.5505 + i * 0.001,
            longitude = -46.6333 + i * 0.001,
            accuracy = 5f,
        )
    }

    private fun validateAgainstXsd(gpx: String) {
        val schemaFile = findSpecFile("gpx-export.xsd")
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(schemaFile)
        val validator = schema.newValidator()
        // Throws org.xml.sax.SAXException on any structural violation — a call that returns
        // normally IS the assertion that `gpx` is valid against the real XSD.
        validator.validate(StreamSource(StringReader(gpx)))
    }

    @Test
    fun `generated GPX validates against gpx-export xsd with exactly one trkpt per point`() {
        val pts = points(5)
        val gpx = GpxExporter.export(pts)

        validateAgainstXsd(gpx)

        val trkptCount = Regex("<trkpt\\b").findAll(gpx).count()
        assertEquals(pts.size, trkptCount)
    }

    @Test
    fun `a single-point session also validates against gpx-export xsd`() {
        val gpx = GpxExporter.export(points(1))

        validateAgainstXsd(gpx)
        assertEquals(1, Regex("<trkpt\\b").findAll(gpx).count())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `exporting a session with no collected points is rejected`() {
        GpxExporter.export(emptyList())
    }

    @Test
    fun `time elements are ISO-8601 xs dateTime and lat lon are plain decimals`() {
        val gpx = GpxExporter.export(points(2))

        assertTrue(
            "expected an ISO-8601 <time> element",
            Regex("<time>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z</time>").containsMatchIn(gpx),
        )
        assertTrue(gpx.contains("lat=\"-23.5505\""))
        assertTrue(gpx.contains("lon=\"-46.6333\""))
    }

    @Test
    fun `an out-of-range latitude fails xsd validation`() {
        val invalid = points(1).map { it.copy(latitude = 200.0) }
        val gpx = GpxExporter.export(invalid)

        try {
            validateAgainstXsd(gpx)
            throw AssertionError("Expected XSD validation to fail for an out-of-range latitude")
        } catch (expected: org.xml.sax.SAXException) {
            // expected: 200.0 violates latitudeType's maxInclusive="90.0"
        }
    }
}
