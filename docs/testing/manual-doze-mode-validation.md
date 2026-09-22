# Manual validation: Doze mode and network isolation

T13 (Phase 8) closes RF-02/RNF-02/RNF-03/RNF-04 with an automated instrumented test
(`TrackingSessionE2ETest`) that proves the create -> collect -> finish -> persist pipeline works
end to end against a real Room database. That test cannot exercise two things a
plain emulator run does not represent honestly:

1. **Real Doze mode.** Doze is a system-level power state driven by the device being stationary,
   unplugged, and screen-off for a sustained period, with maintenance windows scheduled by the
   real `DeviceIdleController`. `adb shell dumpsys deviceidle force-idle` *simulates* the state
   transitions for broadcast/testing purposes, but does not reproduce the actual scheduling jitter
   of maintenance windows on a real device, nor OEM-specific background-restriction layers (e.g.
   manufacturer battery managers) that sit on top of stock Doze. Genuine confidence here requires
   a physical device left alone long enough to enter Doze for real.
2. **Absence of network traffic**, which requires observing the device's actual radio/interface
   activity, not just reading the app's manifest.

This checklist is for a human running the app on a **physical Android device** (API 29+) that has
Google Play services installed. Record results directly in this file (copy the tables per run) or
in a linked issue/test report — either is fine, as long as the raw observations (not just
pass/fail) are kept, since the whole point of RNF-03 is to have real drift numbers on record.

## What FusedLocationProviderClient actually guarantees under Doze — read before testing

Be honest about the ceiling here so a "point stopped appearing" result during Doze is correctly
read as *expected degraded behavior*, not a regression:

- Android's Doze mode restricts network access and defers jobs/alarms/syncs for apps in the
  background. Location updates for background apps are explicitly throttled by the platform:
  since Android 8 (independent of Doze), background location updates are limited to a **few
  times per hour** for apps that are not in the foreground. A **foreground service** (which is
  what `LocationForegroundService`/T06 runs, with `foregroundServiceType="location"`) is Android's
  documented escape hatch from that background throttle — it is the reason this app uses a
  foreground service at all rather than a plain background location callback.
- Even with an active foreground service, deep Doze can still coalesce or delay location fixes:
  GPS chip duty-cycling, network location backend availability, and OEM-specific "deep sleep"
  layers (Samsung, Xiaomi, Huawei, etc., which often restrict foreground services more
  aggressively than stock Android) can all increase the real interval between fixes well beyond
  the configured `SamplingInterval`.
- There is **no documented numeric bound** on this drift from Google, and this project's own
  RNF-03 explicitly declares the sampling interval "best-effort" with "no guaranteed tolerance"
  for exactly this reason. The correct expectation for this checklist is: **points keep being
  recorded (the foreground service is not killed and does not silently stop collecting), but the
  real interval between consecutive points may drift arbitrarily beyond the configured value while
  Doze is active.** A total collection stall for the checklist's test duration (see below) would
  be a genuine regression worth filing; occasional large per-point drift values are not.
- `observedIntervalDriftMillis` (persisted on every point after the first, see
  `GpsPointEntity`/`LocationCollector`) is exactly the mechanism this app uses to make that drift
  visible after the fact instead of promising a bound it cannot keep — this checklist's job is to
  confirm that mechanism keeps working, and to record what real drift looks like on a real device.

## Prerequisites

- A physical Android device, API 29+, with Google Play services, USB debugging enabled.
- The app installed in a debug build with `adb logcat`/`adb shell dumpsys batterystats` available.
- Outdoors or near a window with a real GPS signal (an indoor-only fix will itself show large
  gaps that are a signal quality artifact, not a Doze artifact — don't conflate the two).
- A second device or web page to monitor Wi-Fi/router traffic if using the router-log method
  below (optional — airplane-mode-except-GPS is sufficient on its own).

## Part A — Doze mode: points keep being recorded

1. Launch the app, start a new session with a short interval (e.g. `10s` or `15s`, so drift is
   easy to see against a small baseline).
2. Confirm the foreground notification ("Rastreamento GPS ativo") appears and stays present.
3. Let the app run in the **foreground** for ~2 minutes; note the number of points recorded so far
   (visible via the live tracking screen's polyline/metrics, or by pulling the DB — see "How to
   inspect the DB" below).
4. Send the app to background (press Home). Turn the screen off. Leave the device completely
   stationary, unplugged, on a stable surface, for at least **20-30 minutes** — real Doze does not
   engage instantly; the platform requires a period of "screen off + stationary + unplugged"
   before the first Doze window, and re-evaluates at increasing intervals afterward.
5. Optionally accelerate the transition for verification purposes with:
   ```
   adb shell dumpsys battery unplug
   adb shell dumpsys deviceidle force-idle
   ```
   Note in your results if you used this shortcut — it is a useful sanity check that the
   foreground service survives Doze's *mechanism*, but is not a substitute for step 4's real,
   unforced Doze entry when validating real-world drift numbers.
6. After the wait, wake the device and stop the session.
7. Export the session as CSV (Export UI, T12) and inspect it, or pull the on-device DB directly.
8. Record, per consecutive point pair spanning the Doze window:
   - configured interval,
   - real observed interval (`timestamp[k] - timestamp[k-1]`),
   - `observedIntervalDriftMillis` as persisted (should equal the above minus the configured
     interval, in milliseconds).

### Results table (fill in per run)

| # | timestamp (device-local) | configured interval (s) | real interval (s) | drift (ms) | notes (e.g. "screen off", "Doze forced", "still in foreground") |
|---|---------------------------|--------------------------|--------------------|------------|-------------------------------------------------------------------|
|   |                           |                          |                    |            |                                                                     |

**Pass condition:** at least one new point is recorded after the device has been in Doze for the
full wait period (i.e., collection did not permanently stall) — the exact drift value is expected
to be nonzero and is not itself a failure. **Fail condition:** zero points recorded for the entire
Doze window, or the foreground service/notification silently disappears without the session having
been stopped by the user.

## Part B — Zero network traffic during a session

The app declares no `<uses-permission android:name="android.permission.INTERNET"/>` (confirmed
structurally since T01 — `app/src/main/AndroidManifest.xml` has no `INTERNET` permission, so the
OS itself blocks any socket the app's own process could open). This part is about confirming that
guarantee holds in practice, including from bundled dependencies (Play Services Location, Google
Maps SDK) that run in a different process/permission context.

Two independent methods — do at least one, ideally both on separate runs:

### Method 1 — airplane mode + Wi-Fi/GPS re-enabled

1. Enable Airplane mode.
2. Re-enable Wi-Fi (or, if testing purely on GPS with no network at all, leave Wi-Fi off too —
   this is the stricter variant and is preferred if the device's GPS chip still gets a fix without
   any network-assisted location).
3. Start a session, let it collect points for several minutes (confirm points ARE being recorded —
   this validates GPS still works without network, and implicitly proves the app path doesn't
   depend on network for its own function), then stop the session.
4. Confirm the session completed normally (status "encerrada", metrics computed, export still
   works) — i.e., no crash or silent failure caused by the lack of network.

### Method 2 — router/traffic monitoring

1. Connect the device to a Wi-Fi network you control (e.g., a router with a traffic/client log, or
   a laptop running `tcpdump`/Wireshark on the same network acting as a hotspot).
2. Start a session, let it run for several minutes while watching the traffic monitor filtered to
   the test device's IP/MAC.
3. Stop the session.
4. Confirm the traffic log shows **no outbound connections attributable to the app's process**
   during the session window. (Play services' own background sync/telemetry for other apps on the
   same device is out of scope — filter specifically for `my-tracks-app`'s process/UID if your
   monitoring tool supports per-app attribution, e.g. `adb shell dumpsys netstats detail` or
   Android's Settings > Network > App data usage, which will show 0 B for this app across the
   session window regardless of Wi-Fi/mobile data being available.)

### Results (fill in per run)

- Method used: airplane-mode-except-GPS / router-log / both
- Session duration monitored:
- Points recorded during the monitored window:
- Any outbound traffic attributed to this app: yes / no (must be "no" to pass)
- Settings > Network > App data usage for this app after the session: ______ B sent / ______ B received (expected: 0 / 0)

**Pass condition:** zero bytes sent or received attributable to the app across the whole session
window, under both methods attempted. **Fail condition:** any nonzero traffic attributable to the
app's own process/UID.

## How to inspect the on-device DB (optional, for either part)

```
adb shell run-as com.mytracksapp cp databases/my-tracks-app.db /data/local/tmp/
adb pull /data/local/tmp/my-tracks-app.db
sqlite3 my-tracks-app.db "SELECT id, sessionId, timestamp, observedIntervalDriftMillis FROM gps_points ORDER BY timestamp;"
```

(Requires a debuggable build for `run-as` to work.)
