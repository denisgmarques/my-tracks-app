package com.mytracksapp.ui.tracking

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel

/** Stable test tags for [MapComponent], used by TrackingScreenTest (UI-02). */
object MapComponentTestTags {
    const val MAP_VIEW = "map_component_map_view"
}

/**
 * One pin to render on [MapComponent] (Phase C follow-up — stop-location pins, RF-06 surfaced on
 * the map). [title]/[snippet] populate the marker's info window (e.g. "Parada" / a formatted stop
 * duration); both are optional since not every caller has that metadata to show.
 */
data class MapMarkerInfo(
    val position: LatLng,
    val title: String? = null,
    val snippet: String? = null,
)

/**
 * T09 — thin Compose wrapper around the real Google Maps SDK for Android
 * ([com.google.android.gms.maps.MapView] / [com.google.android.gms.maps.GoogleMap]).
 *
 * This dependency (`com.google.android.gms:play-services-maps`, declared in T01) predates any
 * first-party Jetpack Compose Maps bindings being pulled into this project, so it's wired up
 * here via [AndroidView] rather than a `maps-compose` composable — same interop pattern Compose
 * itself recommends for classic Android `View`s with no direct Compose equivalent.
 *
 * [polyline] is the exact list this composable renders as a single continuous route overlay,
 * ALWAYS replacing (never appending piecewise to) the previous overlay, so the map's displayed
 * polyline mirrors [TrackingViewModel]'s state exactly on every recomposition: each new point
 * the ViewModel emits becomes the new last vertex (UI-02's AC).
 *
 * [markers] (Phase C follow-up) is the exact set of pins this composable renders, following the
 * identical "always replace, never incrementally append" contract as [polyline]: every time
 * [markers] changes, the previous set of [Marker]s is removed and the new set added from scratch.
 *
 * [onPolylineApplied] is an optional test seam — production callers never set it. It fires with
 * the exact [LatLng] list derived from [polyline] on every recomposition where [polyline]
 * changes, INDEPENDENTLY of whether the real [GoogleMap] has finished initializing yet (that
 * still happens separately, best-effort, whenever `getMapAsync` calls back). This deliberately
 * decouples "what MapComponent computed it should display" from "whether the real Maps SDK
 * renderer subprocess is ready" — the latter needs network access and a real Google Cloud API
 * key this prototype intentionally does not ship (see `app/src/main/res/values/google_maps_api.xml`
 * and `docs/setup/google-maps-api-key.md`), so an instrumented test asserting on this seam isn't
 * at the mercy of Play Services' own initialization timing/availability.
 *
 * [onMarkersApplied] is [onPolylineApplied]'s exact counterpart for [markers], for exactly the
 * same reason: it fires with the [LatLng] positions derived from [markers] on every recomposition
 * where [markers] changes, independent of real-map readiness.
 */
@Composable
fun MapComponent(
    polyline: List<TrackingPolylinePoint>,
    modifier: Modifier = Modifier,
    markers: List<MapMarkerInfo> = emptyList(),
    onPolylineApplied: (List<LatLng>) -> Unit = {},
    onMarkersApplied: (List<LatLng>) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply { id = View.generateViewId() }
    }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var currentOverlay by remember { mutableStateOf<Polyline?>(null) }
    var currentMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }

    DisposableEffect(lifecycleOwner, mapView) {
        mapView.onCreate(Bundle())
        mapView.getMapAsync { map -> googleMap = map }

        // NOTE: ON_DESTROY is deliberately NOT forwarded here. This composition's own `onDispose`
        // below runs on the same activity-destroy pass (Compose disposes the composition as part
        // of handling ON_DESTROY too), so forwarding it here as well would call
        // `mapView.onDestroy()` twice. The underlying Maps SDK dynamite module is not
        // idempotent to that: a second `onDestroy()` call crashes with an NPE deep inside
        // `com.google.maps.api.android.lib6.impl` because the first call already tore down its
        // internal delegate. `onDispose` alone guarantees exactly one `onDestroy()` call.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // UI-02: report the point list MapComponent computed for the current ViewModel state as soon
    // as it changes, regardless of whether the real GoogleMap has finished initializing — see the
    // [onPolylineApplied] doc for why this is intentionally decoupled from map readiness.
    LaunchedEffect(polyline) {
        onPolylineApplied(polyline.map { LatLng(it.latitude, it.longitude) })
    }

    // Phase C follow-up: same decoupling as above, for the pin/marker list.
    LaunchedEffect(markers) {
        onMarkersApplied(markers.map { it.position })
    }

    // Separately (best-effort): once the real map is ready, actually draw/replace the polyline
    // overlay AND the markers on it every time the ViewModel's state changes.
    //
    // Camera framing matters as much as the overlays themselves: `newLatLng` alone only PANS, it
    // never zooms, so at the map's default (world-view) zoom level a walking-scale route is a
    // handful of meters — visually indistinguishable from a single point. A single collected
    // point (or a single marker, with no route yet) zooms in close (street level); two or more
    // positions overall (route vertices AND marker pins combined) fit the camera to their
    // bounding box (with padding) so everything collected/placed so far is always framed, not
    // just the route's last vertex — this is why marker positions are folded into the SAME bounds
    // calculation as the polyline's, not computed separately.
    LaunchedEffect(polyline, markers, googleMap) {
        val map = googleMap ?: return@LaunchedEffect

        currentOverlay?.remove()
        currentMarkers.forEach { it.remove() }

        val latLngPoints = polyline.map { LatLng(it.latitude, it.longitude) }
        currentOverlay = if (latLngPoints.size >= 2) {
            map.addPolyline(PolylineOptions().addAll(latLngPoints))
        } else {
            null
        }

        currentMarkers = markers.mapNotNull { marker ->
            val options = MarkerOptions().position(marker.position)
            marker.title?.let { options.title(it) }
            marker.snippet?.let { options.snippet(it) }
            map.addMarker(options)
        }

        val allPositions = latLngPoints + markers.map { it.position }
        when {
            allPositions.size == 1 -> {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(allPositions.first(), 18f))
            }
            allPositions.size >= 2 -> {
                val bounds = LatLngBounds.builder().apply {
                    allPositions.forEach { include(it) }
                }.build()
                // `newLatLngBounds` throws if the MapView hasn't completed its first layout pass
                // yet (size still 0x0) — a real race the very first time a point arrives right as
                // the map surface attaches. Falls back to a plain pan on the last position rather
                // than crashing; the very next change recomputes bounds and self-corrects.
                try {
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                } catch (e: IllegalStateException) {
                    FileLogger.log(
                        LogLevel.WARN,
                        "MapComponent",
                        "Bounds camera update failed, falling back to pan",
                        e,
                    )
                    map.moveCamera(CameraUpdateFactory.newLatLng(allPositions.last()))
                }
            }
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .testTag(MapComponentTestTags.MAP_VIEW),
        factory = { mapView },
    )
}
