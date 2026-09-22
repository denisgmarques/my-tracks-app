# Google Maps SDK for Android — API key setup

`TrackingScreen`/`MapComponent` (T09, Phase 7) render the live tracking polyline with the
Google Maps SDK for Android (`com.google.android.gms.maps.MapView`/`GoogleMap`). That SDK
requires a Google Cloud API key to fetch and render map tiles.

## Current state

The real key lives **only** in `local.properties` (git-ignored, never committed), as:

```properties
MAPS_API_KEY=your-real-key-here
```

`app/build.gradle.kts` reads it at build time and injects it as a generated string resource
(`resValue("string", "google_maps_api_key", mapsApiKey)`), which `AndroidManifest.xml`'s
`com.google.android.geo.API_KEY` meta-data references via `@string/google_maps_api_key`. If
`local.properties` has no `MAPS_API_KEY` entry, the build falls back to the placeholder
`YOUR_API_KEY_HERE` — the app still builds and runs, `MapComponent` still initializes a real
`GoogleMap` instance, and the polyline/metric state-management all work correctly, but the map
surface shows a blank/grey view and Play services logs an authorization/API-key error. This does
not affect UI-02 (polyline tracks new points) or UI-03 (5 metrics visible), which are both about
state, not tile rendering.

There is no `google_maps_api.xml` resource file anymore — the string resource is generated
purely from `local.properties`, so there is no checked-in file a developer could accidentally
leave a real key in.

## Getting a real key

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the **Maps SDK for Android** API for that project.
3. Create an API key, then restrict it (Credentials → your key → "Application restrictions" →
   Android apps → Add):
   - **Package name:** `com.mytracksapp`
   - **SHA-1 certificate fingerprint:** get it from the signing keystore in use. For a debug
     build with the default AGP debug keystore:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
       -storepass android -keypass android
     ```
     (the default debug keystore/password are the same on every machine with the Android SDK —
     not a secret, just identifies "any debug build"). For a release build, run the same command
     against your real release keystore and add that SHA-1 as a **second** entry — a key can be
     restricted to multiple package+fingerprint pairs.
4. Add `MAPS_API_KEY=<the real key>` to `local.properties` at the repo root. Never add it
   anywhere else (source files, `AndroidManifest.xml`, committed resources, chat, issue
   trackers).

## If a real key is ever accidentally committed

Treat it as compromised: revoke/regenerate it in Google Cloud Console immediately, don't just
remove it from a later commit (it stays in git history). Restricting the key by package name +
SHA-1 (above) limits the blast radius of a leak — an unrestricted key could be used from any app.

## Future follow-up

If this project grows a CI pipeline or multiple developers who each need their own key, consider
keeping per-developer keys out of a single shared `local.properties` convention (e.g. an
environment variable read as a fallback in `build.gradle.kts`) so CI doesn't need a checked-in
placeholder either. Not needed yet for this prototype.
