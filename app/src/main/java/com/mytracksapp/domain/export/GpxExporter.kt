package com.mytracksapp.domain.export

import com.mytracksapp.data.local.entity.GpsPointEntity
import java.io.StringWriter
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Generates a GPX 1.1 file for a session's collected points (RF-08, CT-01).
 *
 * Produces exactly the structure `gpx-export.xsd` requires:
 * `<gpx version="1.1" creator="…"><trk><trkseg><trkpt lat="…" lon="…"><time>…</time></trkpt>…
 * </trkseg></trk></gpx>`, one `<trkpt>` per point, `<time>` in ISO-8601 (`xs:dateTime`). No
 * optional GPX elements (`<ele>`, `<extensions>`, `<wpt>`, `<rte>`) are emitted — CT-01/the XSD
 * do not call for them (see the XSD's header comment).
 *
 * Built via `javax.xml` DOM + a `Transformer` (both part of the JDK, no extra dependency) rather
 * than string concatenation, so attribute/text escaping is handled correctly by the standard
 * library.
 */
object GpxExporter {

    /** The GPX 1.1 namespace, matching `gpx-export.xsd`'s `targetNamespace`. */
    const val GPX_NAMESPACE: String = "http://www.topografix.com/GPX/1/1"
    private const val GPX_VERSION = "1.1"
    private const val GPX_CREATOR = "my-tracks-app"

    /**
     * @throws IllegalArgumentException if [points] is empty — `gpx-export.xsd` requires at least
     *   one `<trkpt>` per `<trkseg>` ("um `<trkpt>` por ponto coletado" assumes a collected
     *   session has at least one point; RF-08 only applies to a "sessão concluída", which by
     *   RF-02 only exists if collection was active).
     */
    fun export(points: List<GpsPointEntity>): String {
        require(points.isNotEmpty()) { "Cannot export GPX for a session with no collected points" }

        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = documentBuilderFactory.newDocumentBuilder().newDocument()

        val gpxElement = document.createElementNS(GPX_NAMESPACE, "gpx")
        gpxElement.setAttribute("version", GPX_VERSION)
        gpxElement.setAttribute("creator", GPX_CREATOR)
        document.appendChild(gpxElement)

        val trkElement = document.createElementNS(GPX_NAMESPACE, "trk")
        gpxElement.appendChild(trkElement)

        val trksegElement = document.createElementNS(GPX_NAMESPACE, "trkseg")
        trkElement.appendChild(trksegElement)

        points.forEach { point ->
            val trkptElement = document.createElementNS(GPX_NAMESPACE, "trkpt")
            trkptElement.setAttribute("lat", point.latitude.toString())
            trkptElement.setAttribute("lon", point.longitude.toString())

            val timeElement = document.createElementNS(GPX_NAMESPACE, "time")
            timeElement.textContent = Instant.ofEpochMilli(point.timestamp).toString()
            trkptElement.appendChild(timeElement)

            trksegElement.appendChild(trkptElement)
        }

        val writer = StringWriter()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}
