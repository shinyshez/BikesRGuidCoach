# Viewer Link — Phase 2 Specification

Make the viewing phone the real app: remote clips in the actual gallery and
`VideoPlayerView`, with the pose overlay, drawing and side-by-side comparison.

**Status**: draft. Built so far: the cleartext spike (§4) and M0 (§9: `ClipRef`, `LocalClipSource`).
**Builds on**: `ViewerLink-Phase1-Spec.md` (API contract, security model).
**Roadmap**: `ViewerLink-Roadmap.html`.

---

## 1. Problem

Phase 1 lets a coach watch a run on a second phone, but only in a browser `<video>` with
plain controls. The things a coach needs to point at the rider's position are the pose
overlay, frame stepping, drawing and a side-by-side comparison against an earlier run, and
they all live in the app. Phase 2 puts the recorder's clips in front of those tools.

## 2. Goals

- The viewer app lists the recorder's clips in the gallery, next to its own.
- Tapping a remote clip opens it in `VideoPlayerView` with everything a local clip has:
  pose, draw, frame badge, hold to scrub, pinch to zoom.
- Compare works on any two clips, **including one local and one remote**. For example,
  today's run on the recorder next to a reference run saved on the coach's phone.
- A new clip appears on the viewer as soon as the recorder saves it (SSE, §7).
- **The shipped app keeps Android's strict cleartext default.** No `base-config`
  exemption and no hand-maintained IP list (§4).
- The Phase 1 browser viewer keeps working, unchanged, for viewers without the app.

## 3. Non-goals

Unchanged from Phase 1: the link stays **read-only**. The viewer cannot delete or import on
the recorder (Phase 3 is the first write path). Still out of scope: multi-viewer sync,
transcoding, any cloud path, a hotspot set up by the app (parked, see Phase 1 §5), live
preview.

## 4. The cleartext problem, and the spike that settles it

**Problem.** A native viewer needs this app to make *outbound* `http://` requests to the
recorder. From `targetSdk` 28 up, Android's network security policy refuses cleartext
from the platform HTTP stacks (`HttpURLConnection`, and so media3's
`DefaultHttpDataSource`). Phase 1 planned to fix this with "a production config scoped to
private address ranges", but **that cannot be expressed**: a `<domain-config>` matches
hostnames or exact IP literals, with no CIDR. A hotspot gateway's address can't be listed
in advance either. Android 11+ randomises the hotspot subnet and an iPhone hotspot hands
out `172.20.10.x`. The only config that would work is a global
`cleartextTrafficPermitted="true"`, which switches the protection off for every request
the app makes.

**Resolution.** The policy is enforced by those HTTP stacks, not by the socket layer. So the
viewer talks to the recorder over a raw `Socket`, and the app does not need a cleartext
exemption.

- `ViewerHttpClient`: a GET-only HTTP/1.1 client, pure JVM, about 200 lines. It
  understands exactly what `HttpWriter` produces: `Content-Length` bodies, `Range`, and
  `Connection: close`. It refuses chunked responses rather than misreading them.
- `RemoteClipDataSource`: a media3 `DataSource` on top of it. One `open()` per seek, each a
  Range request. It checks that a `206` starts where it asked, walks forward if a server
  ignores the Range, and maps failures to `PlaybackException` codes.

**Evidence** (`RemoteClipPlaybackInstrumentedTest`). Everything runs against the
device's own non-loopback address, where the debug config's loopback-only exemption does
not apply. That puts the app under exactly the policy a release build has everywhere:

| Test | Proves |
|---|---|
| `policy_refusesCleartextToThisAddress` | The premise: `NetworkSecurityPolicy` says no, and `HttpURLConnection` is refused |
| `exoPlayer_withTheStockHttpDataSource_isRefusedAsCleartext` | Stock media3 fails with `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` |
| `rawSocketClient_readsARangeOverTheSameAddress` | Same address, same policy: the raw client gets a correct `206` slice |
| `dataSource_opensAtAPositionLikeASeekDoes` | `RemoteClipDataSource` returns the right bytes from an arbitrary offset |
| `exoPlayer_preparesAndSeeksThroughTheRawSocketDataSource` | End to end. The test clip keeps `moov` at the tail, so reaching READY at all takes a Range request into the tail; then a seek returns to READY without error |

`ViewerHttpClientTest` covers the client on the JVM against the server's own
`HttpParser`/`HttpRange`/`HttpWriter`, so the two halves of the link are held to the same
bytes.

**Cost accepted.** Two small hand-rolled HTTP implementations now have to agree with each
other. That is fine while this app is on both ends. The moment the recorder is anything
else, or Phase 3 moves the server to a library such as Ktor, the client should move too.

## 5. Pairing

The same QR code serves the browser and the app, so the recorder screen does not change.

- The viewer app gets **Gallery › Connect to recorder**, which opens Google's code scanner
  (`play-services-code-scanner`). It needs no camera permission and adds no camera
  pipeline; Play services draws the UI. Fallback: type the URL shown under the QR.
- The app parses `http://<host>:<port>/?t=<token>`, calls `/api/health` and keeps
  `(host, port, token, recorderId)` in memory for the session.
- **No App Link / intent filter on `http://`.** It cannot be scoped to local addresses, so
  it would offer the app for every web link the phone opens.
- Tokens stay per-session (Phase 1 §7). Restarting Viewer Link on the recorder means
  scanning again. See open question Q1.

**API change, additive:** `/api/health` gains `"recorderId"`, a random UUID persisted on
the recorder. The viewer needs a stable name for a recorder that outlives the token, to key
caches and compare state (§6). `apiVersion` stays `1`. The page ignores unknown fields.

## 6. Clips from two places

### One model for local and remote

Today a clip is a `VideoItem` (`VideoGalleryActivity.kt:35`), and "which clip" is passed
around as a MediaStore `Uri` string. Phase 2 introduces:

```kotlin
sealed class ClipRef {
    data class Local(val id: Long) : ClipRef()
    data class Remote(val recorderId: String, val id: Long) : ClipRef()
    val key: String  // "1337" (unchanged, see below) / "r:<recorderId>:1337"
}
```

This collapses `ClipCatalog` and the gallery's query, which the roadmap promised
Phase 2 would do. A `ClipSource` interface has `LocalClipSource` (the MediaStore query,
**one** implementation shared by the gallery and the server) and `RemoteClipSource`
(`/api/clips` through `ViewerHttpClient`). Merging the queries is the riskiest change
to the gallery in this phase, so it lands on its own, first, with tests (§9, M0).

*As built in M0:* the callers differ in one flag. The server lists with `finishedOnly = true`
(no `IS_PENDING` rows, and pre-Q no zero-duration rows, as `ClipCatalog` did); the gallery
and capture strip list every row, as they always have. The pre-Q duration test would
otherwise hide imports on API 24–28, which are inserted with no `DURATION`. The `ClipSource`
interface arrives with its second implementation in M1.

**Id collisions are real.** `VideoComparisonActivity.kt:134` keys `CompareSyncStore` on
`uri.lastPathSegment`. For `http://…/api/clips/1337` that is `"1337"`, the same key as
local clip 1337. Compare state moves to `ClipRef.key`. A `Local` key stays the bare id it is today,
so pairs already remembered on the phone survive the change. Only remote keys get the prefix.

### Gallery

- A source toggle in the gallery header: **This phone | Recorder (Pixel 7)**. It only
  appears while paired. Merging both into one grid was considered. It muddles "whose
  clip is this" when a delete is attempted, and gains little.
- Remote tiles use `/api/clips/{id}/thumb` through Glide with a custom `ModelLoader` over
  `ViewerHttpClient`. Glide's default HTTP stack is `HttpURLConnection`, and §4 applies to
  it as well.
- Remote tiles do **not** swipe to delete and show no Import control.
- Compare picks can come from both sources. Switching the toggle keeps the picks.

## 7. Playback

`VideoPlayerView` uses the clip in two ways, and they need different handling:

1. **ExoPlayer** plays it. Give it `RemoteClipDataSource` through
   `DefaultMediaSourceFactory`, and a remote clip streams and seeks at once.
2. **`MediaMetadataRetriever`** reads it. It is used for frame metadata
   (`VideoPlayerView.kt:297`) and for **every pose frame** (`processCurrentFrameForPose`,
   `:953`, a new retriever per paused frame). The `Context` overload cannot open `http://`.
   Even if it could, a network read per frame would make the pose toggle crawl.

**Decision: stream to play, download to analyse.** Opening a remote clip starts streaming
playback immediately. In parallel, `RemoteClipCache` downloads the whole file, using the
same client, to `cacheDir/remote-clips/<recorderId>/<id>.mp4`: write to `.part`, then
rename. Clip bytes are immutable for an id (Phase 1 §6). The retriever reads the cached
file, so pose and frame stepping are exactly the local code path. Until the file lands:

- the frame badge uses `/api/clips/{id}/meta` (which exists for exactly this and is still
  unused),
- the Pose toggle shows a short spinner and turns on by itself when the file lands.

An 8s clip is roughly 10–15MB. On a 5GHz hotspot that takes about a second, and several on
2.4GHz, which is about how long it takes to find the right frame and pause. The cache is
pruned LRU to a fixed size (256MB) and cleared when a recorder id is forgotten.

`VideoPlayerView.setVideo(uri)` becomes `setClip(PlayableClip)`: a playback `Uri`, the
`DataSource.Factory` to reach it, and a frames `Uri?` that may arrive later. Local clips
build one with the default factory and the same Uri for both, so their behaviour does
not change.

## 8. New clips as they happen: SSE

`GET /api/events` (token-gated like `/api/clips`) holds the response open and writes:

```
event: clip
data: {"id":1338,"name":"MTB_…","dateAdded":1758378731,"durationMs":8004,"sizeBytes":…,"kind":"recording"}
```

and a `: keepalive` comment every 15s, so a viewer that walked out of range shows up as a
failed write instead of a worker parked forever. The stream is not a source of truth. On (re)connect the viewer calls
`/api/clips?since=<newest seen>` to catch up, which is what `since` was built for.

- **Server side.** The recorder already knows when a recording is saved
  (`RecordingCallback.onRecordingFinished`, `RecordingManager.kt:120`). It publishes that into a small broadcast hub. A
  `ContentObserver` on the MediaStore collection catches imports too. Each SSE stream
  takes one worker thread, so `WORKERS` (4) caps concurrent viewers. That is fine, since
  multi-viewer is a non-goal. Say so in the code.
- **Framing.** SSE needs an unbounded body with no `Content-Length`. `HttpResponse` gains
  a streaming variant. The client reads to EOF for bodies without a length, which already
  works and is tested.
- **The browser page** switches from 5s polling to `EventSource` and keeps polling as a
  fallback. That is a small change to `index.html`.

## 9. Milestones

Each milestone is a PR that leaves main shippable.

| | What | Done when |
|---|---|---|
| M0 | `ClipRef`, `LocalClipSource` shared by gallery and server; compare keys move to `ClipRef.key` | Gallery and Viewer Link behave as before; existing tests pass; new tests cover the key migration |
| M1 | Pairing (§5), remote gallery tab, download-then-play | A remote clip opens in `VideoPlayerView` with pose and draw working (it plays from the cached file, so `VideoPlayerView` is untouched) |
| M2 | Stream while downloading (§7): `PlayableClip`, `RemoteClipDataSource` in `VideoPlayerView` | Playback starts before the download finishes; pose becomes available when it does |
| M3 | Compare across sources | A local and a remote clip compare side by side, with lock/offset remembered |
| M4 | SSE (§8) for app and page | A clip saved on the recorder appears on both viewers within a second |

M1 is deliberately "download, then play". It ships the whole feature with no change to
`VideoPlayerView`, and M2 is then a pure latency improvement that can be measured against it.

## 10. Testing

- **JVM:** `ViewerHttpClientTest` (exists). Add SSE framing, `ClipRef` keys and the old-key
  compatibility, and the cache's `.part`/rename and LRU arithmetic.
- **Instrumented:** `RemoteClipPlaybackInstrumentedTest` (exists) is the template.
  Against the non-loopback address: the remote source lists the imported test clip; the
  cache downloads it byte-identical; `VideoPlaybackActivity` opened on a remote clip
  reaches READY and extracts a pose frame from the cached file.
- **Two emulators** are not needed. One device serving itself over its own non-loopback
  address is the real code path; only the hotspot is missing, and that is not our code.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Client and server drift apart | Both are held to the same `HttpParser`/`HttpWriter` bytes in JVM tests; the client refuses what it doesn't understand instead of guessing |
| Merging the MediaStore queries regresses the gallery | M0 alone, before any remote work, behind the existing gallery tests |
| Pose feels slow on a 2.4GHz hotspot while the clip downloads | Visible spinner on the toggle; the download starts at open, not at pause |
| Glide pulls in the platform HTTP stack for thumbnails | Custom `ModelLoader` over `ViewerHttpClient`; covered by the same non-loopback test |
| An SSE stream holds a worker forever | Keep-alive write fails when the viewer goes away; caps documented; viewer reconnects with backoff |

## 12. Open questions

- **Q1. Keep the token across Viewer Link restarts?** Re-scanning every session is fine
  for a coach who pairs once at the trailhead. A stable token until "Reset pairing"
  would be friendlier, but it weakens Phase 1's "a new token every start". Recommend:
  leave as is for Phase 2, and revisit with Phase 3's security review.
- **Q2. Should the viewer be able to keep a remote clip?** A download into its own
  gallery is one button once the cache exists. It is a copy on the viewer, not a write
  to the recorder, so it stays inside "read-only". Recommend: yes, in M1 or M3, as
  "Save to this phone".
