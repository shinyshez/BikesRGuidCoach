# Viewer Link — Phase 1 Specification

Let a second phone browse and play clips from the tripod phone, over a local network,
without touching the tripod phone.

**Status**: implemented and merged.
**Scope**: Phase 1 only — the recorder serves, the viewer uses a web browser.
**Roadmap**: `ViewerLink-Roadmap.html` summarises §3 as a phase plan.

---

## 1. Problem

The recording phone is mounted on a tripod, framed on a feature, and often armed in Auto
mode. Reviewing a run today means walking to the tripod and handling the phone, which
costs the framing and interrupts detection. A coach wants to stand with the rider at the
bottom of the feature and pull up the run that just happened.

Every push-based transfer (Quick Share, Bluetooth, the share sheet) requires a tap on the
*sending* device, so none of them solve this. The recorder must be a **listener** and the
viewer must drive. That single constraint decides the architecture: an embedded HTTP
server on the recorder.

## 2. Goals

- From a second device, list every clip the recorder can see, newest first.
- Play any clip, with scrubbing, without waiting for a full download.
- Pair in under ten seconds, once, before the phone goes on the tripod.
- Work with no internet and no infrastructure beyond a phone hotspot.
- **No app install on the viewer.** The viewer is a browser, so an iPhone or a borrowed
  phone works. This is the main reason Phase 1 is a web page rather than a second app mode.
- Zero interaction with the recorder once it is mounted.

## 3. Non-goals (deferred)

| Deferred to | What |
|---|---|
| Phase 1.5 | Local-only hotspot (`startLocalOnlyHotspot`) so the app provisions the network itself |
| Phase 2 | Native "Viewer mode" — remote clips in the real gallery and `VideoPlayerView`, with pose overlay, drawing and side-by-side comparison |
| Phase 2 | Server-sent events so a new clip appears on the viewer the instant it is saved |
| Phase 3 | Control channel — arm/disarm Auto, trigger a recording, change duration from the viewer |
| Not planned here | Live camera preview streaming. `CameraManager.kt:64,141,144` already binds Preview + `VideoCapture<Recorder>` + `ImageAnalysis`, which is the three-use-case limit on most devices. There is no spare stream for an encoder tap, and re-encoding from `ImageAnalysis` would steal frames from the detector. Treat it as a separate project |

Also explicitly out of scope for Phase 1: deleting or importing from the viewer (the API is
**read-only**), multi-viewer sync, transcoding, and any cloud path.

## 4. User flow

**Recorder, once, before mounting:**

1. Turn on the phone's Wi-Fi hotspot from quick settings (ordinary Android hotspot — the
   app does not provision it in Phase 1; see §5).
2. Settings → **Viewer Link** → toggle on.
3. The screen shows a QR code and the URL underneath it in plain text as a fallback.

**Viewer:**

4. Join the recorder's hotspot.
5. Scan the QR with the camera app; a browser opens the clip list.
6. Tap a clip to play. Pull to refresh for new runs.

The recorder then goes on the tripod and is not touched again. The Viewer Link screen can
be backed out of — the server keeps running as a foreground service (§9).

## 5. Transport decision

**Phase 1 uses whatever IP network both phones are already on.** In practice that is the
recorder's own Wi-Fi hotspot, enabled by hand from quick settings. It can equally be a
shared home/clubhouse Wi-Fi network.

Rationale for not doing `startLocalOnlyHotspot` now:

- It is the single biggest source of OEM-specific behaviour in this design (some devices
  require Location to be on, some kill the hotspot when the app backgrounds, the
  passphrase retrieval API differs across 26/28/30).
- It is fully additive later: it only changes how the phones end up on the same subnet,
  not a single line of the server or the API.
- Manual hotspot is a one-time setup step that happens before the tripod is set, so it
  does not violate the "don't touch the recorder" requirement.

Phase 1.5 adds the app-provisioned hotspot and puts the SSID and passphrase into the QR
payload so the viewer joins and opens the page in one scan.

## 6. HTTP API

Read-only. HTTP/1.1. JSON is UTF-8. All timestamps are epoch **seconds** to match
`MediaStore.Video.Media.DATE_ADDED`; all durations are **milliseconds** to match
`DURATION`.

### `GET /` → the viewer page

Serves the single-file HTML viewer from `assets/viewer/index.html` (§8). Accepts the token
as `?t=`, sets it as a session cookie, then redirects to `/` so the token leaves the
address bar.

### `GET /api/health`

```json
{ "ok": true, "device": "Pixel 7", "clips": 42, "apiVersion": 1 }
```

Unauthenticated, so the viewer page can show a useful "recorder unreachable" state.
Deliberately leaks nothing but a device model.

### `GET /api/clips`

Backed by the same MediaStore query the gallery uses (`VideoGalleryActivity.kt:241-280`) —
`DISPLAY_NAME LIKE 'MTB_%'`, sorted `DATE_ADDED DESC` — so the remote list matches the
local gallery exactly, imports included.

```json
{
  "clips": [
    {
      "id": 1337,
      "name": "MTB_2026-09-20-14-31-07-482.mp4",
      "dateAdded": 1758378667,
      "durationMs": 8012,
      "sizeBytes": 9437184,
      "kind": "recording"
    }
  ]
}
```

- `kind` is `"recording"` when the name matches `MTB_yyyy-MM-dd-HH-mm-ss-SSS.mp4`,
  otherwise `"import"` — the same rule the gallery uses for its `import` tag.
- Optional `?since=<epochSeconds>` returns only clips with `dateAdded > since`, so the
  viewer can poll cheaply (§8) and Phase 2 can reuse it for SSE catch-up.
- **In-progress recordings must be excluded.** On API 29+ filter
  `MediaStore.Video.Media.IS_PENDING = 0`; below that, drop rows with `durationMs <= 0`.
  Serving a half-written MP4 is the most likely way to hand the viewer a broken file.

### `GET /api/clips/{id}` → the MP4

The one endpoint that has to be right.

- **Must** send `Accept-Ranges: bytes` and honour `Range: bytes=start-end`, replying `206`
  with a correct `Content-Range` and `Content-Length`. ExoPlayer and every mobile browser
  seek by range request; without this, scrubbing downloads the whole file each time.
- Unsatisfiable range → `416` with `Content-Range: bytes */<size>`.
- `Content-Type: video/mp4`, `Cache-Control: private, max-age=3600` (clip bytes are
  immutable for a given id).
- Read via `contentResolver.openFileDescriptor(uri, "r")`; take the length from the
  descriptor, not from the MediaStore `SIZE` column, which can lag.
- `HEAD` is supported (same headers, no body).

### `GET /api/clips/{id}/thumb`

JPEG, long edge 320px, from `MediaStore.Video.Thumbnails`/`loadThumbnail` on Q+ or
`MediaMetadataRetriever.getFrameAtTime` below. Cached in `cacheDir/viewer-thumbs/<id>.jpg`;
generation is the only CPU-notable work the server does, so it is capped at two concurrent
generations.

### `GET /api/clips/{id}/meta` (optional, for Phase 2)

Frame rate and frame count via `MediaMetadataRetriever`, computed on demand and cached in
memory. Phase 2's frame badge needs this because
`VideoPlayerView.kt:296-297` calls `MediaMetadataRetriever.setDataSource(context, uri)`,
an overload that does **not** accept an `http://` URL — the remote player must be handed
these numbers rather than deriving them. Phase 1's web page does not use it; the endpoint
exists so Phase 2 does not need an API version bump.

### Errors

Plain JSON `{"error":"..."}` with `401` (bad/missing token), `404` (unknown id) and
`416` (bad range).

Missing media-read permission is **not** an error. MediaStore still returns the clips this
install owns, so failing the request would hide clips the viewer could otherwise watch.
`/api/clips` instead carries `"mediaPermission": false` and the page explains that older
clips are hidden — the same behaviour as the gallery, which lists what it can see.

## 7. Security model

The threat is someone else on the same Wi-Fi, not a targeted attacker. Proportionate
measures:

- **Per-session token**, 128 bits from `SecureRandom`, base64url. Regenerated every time
  the server starts. Compared in constant time.
- Accepted as `?t=` (required — a `<video src>` cannot set headers), then stored as an
  `HttpOnly; SameSite=Lax` cookie so it stops appearing in URLs. Lax rather than
  Strict because a QR scanner opening the link counts as a cross-site navigation,
  and a Strict cookie is withheld on the redirect that follows.
- **Bind to the non-loopback local address only**, never `0.0.0.0` on a network the app did
  not expect. Reject requests whose `Host` header is not the address the QR advertised
  (cheap DNS-rebinding guard).
- No CORS headers at all — the page is same-origin with the API.
- Plain HTTP. TLS with a self-signed cert on a local IP produces a browser warning that
  trains users to click through, which is worse than the risk it removes on a private
  hotspot. Documented as a conscious decision; revisit if Phase 3 adds a control channel,
  because *writes* change the calculus.
- Server is off by default and stops when the toggle is turned off or the app is killed.

The realistic residual risk: someone on the same hotspot who can guess the port can reach
`/api/health` and see a device model. Accepted.

## 8. The served viewer page

One self-contained HTML file in `app/src/main/assets/viewer/index.html` — no build step, no
CDN (there is no internet on that hotspot), inline CSS and JS.

- Dark, matching the app's gallery: 9:16 tiles, cropped, grouped by day, time of day and
  duration on each tile, `import` tag where `kind == "import"`.
- Tap a tile → full-screen `<video controls playsinline src="/api/clips/{id}">`. Native
  controls are enough for Phase 1; the pose overlay and drawing tools are what Phase 2 buys.
- Polls `GET /api/clips?since=<newest seen>` every 5s while the list is visible, and stops
  polling while a video is playing. New clips animate in with a NEW tag, mirroring the
  Today's clips strip on the capture screen.
- Offline/unreachable state with a retry button, driven by `/api/health`.

Keeping this page under ~15KB matters more than it looks: it is served by a hand-rolled
server to a phone that may be on a slow link.

## 9. Android implementation notes

**New permissions** (the app currently declares no networking permissions at all):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

`INTERNET` is required even to open a listening socket. `POST_NOTIFICATIONS` (33+) only
affects whether the foreground-service notification is *visible*; the service runs either
way, so a denial is not fatal and must not block the feature.

**Cleartext traffic**: Android blocks cleartext by default from `targetSdk` 28 up, but only
for *outbound* requests made by this app's HTTP stacks. The server is a raw `ServerSocket`
accepting inbound connections, and the viewer is a separate browser app, so Phase 1 needs no
exemption in the shipped app. The instrumented test does drive the server through
`HttpURLConnection`, so a **debug-only** network security config permits cleartext to
loopback (`app/src/debug/`). **Phase 2 will need more**: a native viewer mode points media3
at an `http://` URL, which is an outbound cleartext request and will be refused — it needs a
production config scoped to private address ranges before that can work.

**Media read permission**: the server reads MediaStore on behalf of a remote caller, so it
needs the same permission the gallery does. Reuse `MediaPermissions` — request it from the
Viewer Link screen, and return `503` from `/api/clips` when it is missing rather than
silently serving an empty list.

**Foreground service** (`ViewerLinkService`, `foregroundServiceType="dataSync"`): required
so the server survives the recorder's screen turning off and the app backgrounding. The
notification shows the URL and a Stop action. `targetSdk 34` mandates the declared type.

**Threading**: `ServerSocket` accept loop on a dedicated thread; a bounded pool of 4 worker
threads. HTTP/1.1 keep-alive with a 15s idle timeout — worth the small complexity, because
seeking a video fires many range requests and a connect-per-request is visibly laggy.

**Port**: try 8080, then the next free port; advertise whatever was actually bound.

**Local address**: enumerate `NetworkInterface` for a non-loopback IPv4, preferring an
interface that looks like an AP (`ap0`, `wlan1`, `swlan0`) so hotspot mode resolves
correctly, then `wlan0`. Show the chosen address as text under the QR so a user can correct
for it if the guess is wrong.

**QR generation**: add `com.google.zxing:core:3.5.3` — pure Java, encode-only, no Android
artifact and no camera dependency. Render the `BitMatrix` to a `Bitmap` directly.

## 10. Files

**New**

| File | Purpose |
|---|---|
| `viewer/ViewerLinkServer.kt` | Socket accept loop, HTTP/1.1 parse, routing, Range handling |
| `viewer/ViewerLinkRoutes.kt` | The endpoints in §6 |
| `viewer/ClipCatalog.kt` | MediaStore query → `ClipDto`, shared shape with the gallery |
| `viewer/ClipThumbnails.kt` | Thumbnail generation and disk cache |
| `viewer/ViewerLinkService.kt` | Foreground service owning the server lifecycle |
| `viewer/ViewerLinkActivity.kt` | QR + URL + on/off, permission prompt, connection state |
| `viewer/HttpRange.kt` | `Range` parsing/validation, pure and unit-testable |
| `viewer/NetworkAddress.kt` | Interface selection |
| `assets/viewer/index.html` | The viewer page (§8) |
| `res/layout/activity_viewer_link.xml` | QR screen |

**Changed**

| File | Change |
|---|---|
| `AndroidManifest.xml` | Permissions above; `ViewerLinkActivity`; `ViewerLinkService` |
| `res/xml/preferences.xml` | New **Viewer Link** category after Remote Control (order 450), with one entry opening the screen. The on/off control lives on that screen rather than in Settings, so turning it on and scanning the code it produces are the same step |
| `SettingsActivity.kt` | Wire the new preference to `ViewerLinkActivity`, alongside the existing `zoom_test`/`detection_tuning` handlers (lines 149-155) |
| `SettingsManager.kt` | `KEY_VIEWER_LINK_ENABLED`, default `false` |
| `app/build.gradle.kts` | `com.google.zxing:core:3.5.3` |

`VideoGalleryActivity` is deliberately **not** touched in Phase 1 — `ClipCatalog`
duplicates its query rather than refactoring it, so the feature cannot regress the gallery.
Phase 2 collapses the two.

## 11. Testing

**Unit (JVM, runs in `unit-tests.yml`)** — the parsing is where the bugs will be:

- `HttpRange`: open/closed/suffix ranges, `bytes=0-`, `bytes=-500`, past-EOF, malformed,
  multi-range (ignored, serving the full entity per RFC 7233), off-by-one on
  `Content-Range`.
- Request-line and header parsing, including an oversized header guard.
- `MTB_…` → `kind` classification, including the import fallback.
- Clip JSON shape.
- Constant-time token comparison.

**Instrumented (`instrumented-tests.yml`, on the CI emulator)** — all of this is loopback,
so it genuinely runs in CI:

- Start the server, `GET /api/health`, assert 200.
- `/api/clips` with no token → 401; with token → 200.
- Seed a small MP4 into MediaStore, assert it appears, fetch it whole, then fetch
  `bytes=100-199` and assert 206, `Content-Range` and exactly 100 bytes.
- Pending row is excluded from `/api/clips`.
- Media permission revoked → 503.

**Manual, two devices** (cannot be automated here):

- Pair by QR, list, play, scrub mid-clip, record a new run and see it arrive via polling.
- Recorder screen off → viewer still works.
- Viewer walks out of range and back → page recovers.
- Thermals: 30 minutes of Auto-mode detection with the server running, watching the
  existing `PerformanceMonitor` for detection FPS drop. **Target: no measurable drop in
  detection FPS while idle-serving**; a clip transfer may cost frames briefly, and that is
  acceptable since it happens between runs.

## 12. Risks

| Risk | Mitigation |
|---|---|
| Range handling subtly wrong → scrubbing re-downloads, or ExoPlayer fails in Phase 2 | Heaviest unit + instrumented coverage in the plan sits here |
| Hotspot IP guessed wrong on some OEM | Address shown as text under the QR; interface preference list is a one-line change |
| Battery: detection + encode + hotspot + serving | Foreground service is visible and stoppable; off by default; measure before shipping |
| Hand-rolled HTTP grows into a liability | Bounded by a read-only API of five endpoints. If Phase 3 adds writes, revisit and consider Ktor CIO |
| Serving a clip still being written | `IS_PENDING` filter, called out in §6 |
| Phase 2's media3 playback refused by the cleartext policy | Known now (§9), not a surprise later; needs a production network security config scoped to private ranges |

## 13. Acceptance criteria

1. With Viewer Link on and both phones on the same network, scanning the QR opens a clip
   list matching the recorder's gallery, including imports.
2. A clip plays in mobile Safari and Chrome, and scrubbing to the middle of an 8s clip
   starts playing in under a second on a hotspot link.
3. A run recorded after the page is open appears within 5s without a manual refresh.
4. Requests without a valid token get 401 on every `/api/*` route except `/api/health`.
5. The recorder is not touched between mounting it and the end of the session.
6. Turning the toggle off stops the server and removes the notification.
7. `./gradlew lint testDebugUnitTest connectedDebugAndroidTest` green.

---

## Estimate

Roughly 700-900 lines of Kotlin plus the HTML page. The server and Range handling are
about half of it; the QR screen and settings wiring are small. The unknowns are all in
device behaviour (hotspot addressing, thermals), not in the code.
