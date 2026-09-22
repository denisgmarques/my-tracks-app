# Google Maps SDK for Android — API key setup

`TrackingScreen`/`MapComponent` (T09, Phase 7) render the live tracking polyline with the
Google Maps SDK for Android (`com.google.android.gms.maps.MapView`/`GoogleMap`). That SDK
requires a Google Cloud API key to fetch and render map tiles.

## Current state (prototype)

`app/src/main/res/values/google_maps_api.xml` ships a **placeholder** string resource:

```xml
<string name="google_maps_api_key" translatable="false">YOUR_API_KEY_HERE</string>
```

referenced from `AndroidManifest.xml`'s `com.google.android.geo.API_KEY` meta-data. This is
intentional: **no real key is committed to this repository.** With the placeholder in place,
the app builds and runs, `MapComponent` initializes a real `GoogleMap` instance, and the
polyline/metric state-management all work correctly — but the map surface itself will show a
blank/grey view and Play services will log an authorization/API-key error, since
`YOUR_API_KEY_HERE` is not a valid key. This does not affect any acceptance criteria for this
feature: UI-02 (polyline tracks new points) and UI-03 (5 metrics visible) are both about state,
not tile rendering.

## Before the map will actually render tiles

A human developer must:

1. Create or select a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the **Maps SDK for Android** API for that project.
3. Create an API key and restrict it to this app's package name (`com.mytracksapp`) and your
   debug/release signing certificate's SHA-1 fingerprint.
4. Replace `YOUR_API_KEY_HERE` in `app/src/main/res/values/google_maps_api.xml` with that real
   key, **locally only**.
5. **Never commit the real key.** If you accidentally stage a real value, revert it before
   committing.

## Recommended follow-up (not implemented here)

For a real (non-prototype) project, the key should not live in a checked-in resource file at
all, even as a value a developer edits locally, since it's easy to accidentally commit. The
standard approach is to:

- Store the real key in `local.properties` (already git-ignored by the Android Gradle Plugin
  template) as `MAPS_API_KEY=...`.
- Read it in `app/build.gradle.kts` via `Properties()`/`local.properties` and inject it as a
  manifest placeholder (`manifestPlaceholders["MAPS_API_KEY"] = ...`) or `BuildConfig` field.
- Reference `${MAPS_API_KEY}` from the manifest's `com.google.android.geo.API_KEY` meta-data
  instead of a static `@string` resource.

This wasn't implemented for this prototype because there is no CI/signing pipeline yet to
motivate keeping the key out of a resource file — the placeholder-in-resource approach already
satisfies "no real key committed." Revisit this if/when the project gets a CI pipeline or
multiple developers who each need their own key.
