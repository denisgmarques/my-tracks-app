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
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/** Stable test tags for [MapComponent], used by TrackingScreenTest (UI-02). */
object MapComponentTestTags {
    const val MAP_VIEW = "map_component_map_view"
}

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
 * [onPolylineApplied] is an optional test seam — production callers never set it. It fires with
 * the exact [LatLng] list derived from [polyline] on every recomposition where [polyline]
 * changes, INDEPENDENTLY of whether the real [GoogleMap] has finished initializing yet (that
 * still happens separately, best-effort, whenever `getMapAsync` calls back). This deliberately
 * decouples "what MapComponent computed it should display" from "whether the real Maps SDK
 * renderer subprocess is ready" — the latter needs network access and a real Google Cloud API
 * key this prototype intentionally does not ship (see `app/src/main/res/values/google_maps_api.xml`
 * and `docs/setup/google-maps-api-key.md`), so an instrumented test asserting on this seam isn't
 * at the mercy of Play Services' own initialization timing/availability.
 */
@Composable
fun MapComponent(
    polyline: List<TrackingPolylinePoint>,
    modifier: Modifier = Modifier,
    onPolylineApplied: (List<LatLng>) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply { id = View.generateViewId() }
    }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var currentOverlay by remember { mutableStateOf<Polyline?>(null) }

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

    // Separately (best-effort): once the real map is ready, actually draw/replace the polyline
    // overlay on it every time the ViewModel's state changes.
    LaunchedEffect(polyline, googleMap) {
        val map = googleMap ?: return@LaunchedEffect
        currentOverlay?.remove()
        val latLngPoints = polyline.map { LatLng(it.latitude, it.longitude) }
        currentOverlay = if (latLngPoints.size >= 2) {
            map.addPolyline(PolylineOptions().addAll(latLngPoints))
        } else {
            null
        }
        latLngPoints.lastOrNull()?.let { last -> map.moveCamera(CameraUpdateFactory.newLatLng(last)) }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .testTag(MapComponentTestTags.MAP_VIEW),
        factory = { mapView },
    )
}
