# ABS WearOS Player

A deliberately minimal Audiobookshelf client for Wear OS, built for a Pixel Watch 4
(Wear OS 6-7, API 36). It searches one Audiobookshelf library, downloads books to the
watch, plays them back offline, and syncs the listening position with the server.

It does three things: **search**, **download**, and **play**. There is no speed
control and no sleep timer, and there is no companion phone app — the watch talks
to Audiobookshelf directly.


---

## Requirements

- Audiobookshelf 2.36.x reachable over the network (developed against 2.36.1).
- A normal Audiobookshelf user account. The account needs the **Can Download**
  permission, otherwise `GET /api/items/{id}/file/{ino}/download` returns 403.
- Android Studio (Ladybug or newer) or a JDK 17+ and the Android SDK, with
  platform 36 and build-tools 36 installed.

## Configure

Nothing at build time. Copy the example file and point it at your SDK:

```bash
cp local.properties.example local.properties   # then set sdk.dir
```

Server address, username and password are entered on the watch at first launch.

## Build

```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # offset math and API payload parsing
```

Or open the project in Android Studio and run the `app` configuration.

## Install on the Pixel Watch 4
Put the watch and your computer on the **same Wi-Fi network** — and make sure the watch is actually
joined to Wi-Fi, not just riding the Bluetooth link to your phone
(*Settings → Connectivity → Wi-Fi*).

**1. Turn on developer options**

*Settings → System → About → Versions*, then tap **Build number** seven times.

**2. Turn on ADB debugging**

*Settings → Developer options*:

- **ADB debugging** → on
- **Debug over Wi-Fi** (or **Wireless debugging** on newer builds) → on

**3. Connect**

Wait a few seconds for the IP address and port to appear under *Debug over Wi-Fi*,
then:

```bash
adb connect <watch-ip>:5555
```

If your build shows **Wireless debugging** with a pairing screen instead, pair
first using the code and port from *Pair new device*:

```bash
adb pair <watch-ip>:<pairing-port>     # enter the six-digit code when prompted
adb connect <watch-ip>:<debug-port>    # the port on the main Wireless debugging screen
```

Accept the "Allow debugging?" prompt on the watch. Confirm with `adb devices`.

**4. Install**

```bash
adb -s <watch-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Or straight from Gradle, once exactly one device is connected:

```bash
./gradlew installDebug
```

To disconnect afterwards: `adb disconnect <watch-ip>:5555`.

---

## How it works

### Sign in

`POST /login` with `{ username, password }` and the header `x-return-tokens: true`,
which makes the server return the refresh token in the JSON body instead of an
httpOnly cookie a native client cannot read. The response carries
`user.accessToken` (valid one hour by default), `user.refreshToken` (30 days,
rotated on every use) and `userDefaultLibraryId`, which saves a `/api/libraries`
call.

Both tokens are encrypted with a hardware-backed AES-GCM key from the Android
Keystore before being written to SharedPreferences — the approach the official
Audiobookshelf app uses for its refresh tokens. **The password itself is never
stored**; it is exchanged for tokens at login and then dropped.

When the server answers `401`, the client exchanges the refresh token at
`POST /auth/refresh` (header `x-refresh-token`) and retries the request once.
Concurrent 401s are serialised behind a mutex so a burst triggers one exchange,
and the rotated refresh token is persisted each time. If the refresh is refused,
the session is cleared and the app returns to the login screen.

Signing out keeps downloaded files and positions, so signing back in to the same
account picks them up again. Signing in to a *different* server or user wipes the
local library first, since item ids mean nothing across accounts.

Wear has no masked text input, so the password is visible while it is typed.

### Cover art

`GET /api/items/{id}/cover?width=N&format=jpeg` — the server resizes, which
matters on a watch that may be routing through the Bluetooth proxy. Covers are
requested at 192px for library tiles, 96px for search rows and 384px for the
detail screens, never at full size.

Loading goes through Coil with an OkHttp interceptor that adds the same bearer
token as the rest of `/api`, reading it per request so a token refresh is picked
up without rebuilding the loader. Each download also saves `cover.jpg` next to
the book's audio files, so the library grid keeps working offline; a failed
cover download is never fatal.

Books without cover art get a tinted tile with the title's initials, coloured
from a hash of the title so it stays the same between launches.

### Search

`GET /api/libraries/{libraryId}/search?q={query}&limit=10`. Text entry uses Wear's
standard `RemoteInput` intent, so voice, the on-watch keyboard, and — when a phone
is paired — the phone keyboard all work. Results show title and author; tapping one
opens the Book screen.

### Download

`GET /api/items/{itemId}?expanded=1` supplies `media.tracks`, each carrying `ino`,
`index`, `duration`, `startOffset` and `mimeType`. Each audio file is then fetched
individually from

```
GET /api/items/{itemId}/file/{ino}/download
Authorization: Bearer <token>
```

which is exactly what the official Audiobookshelf Android app does. This covers a
single `.m4b` and a multi-file `.mp3` book with the same code path. Files land in
app-internal storage under `files/books/{itemId}/`, named `{index}-{filename}` so
they stay in track order.

Downloads run in a foreground `CoroutineWorker` with a progress notification.
Before transferring anything the worker calls `ConnectivityManager.requestNetwork`
for a `TRANSPORT_WIFI` network and pins its sockets to it — on a paired watch the
default route is the Bluetooth proxy through the phone, which is far too slow for
audiobook files. If Wi-Fi is not available it tries any unmetered network, and
failing that falls back to the active network, in which case the notification says
"No Wi-Fi — this will be slow".

Partial files are resumed with an HTTP `Range: bytes=N-` request against a `.part`
file, handling 206 (resume), 200 (server ignored the range — start over) and 416
(already complete, or the partial file is stale). A book is marked downloaded only
once every one of its files exists on disk.

### Playback

A `MediaSessionService` owns the ExoPlayer instance, so playback continues with the
screen off and the system media controls and headset buttons work. Multi-file books
become an ExoPlayer playlist; each `MediaItem` carries the track's `startOffset` in
its metadata extras, which is how the player converts between the global book
position and a (track index, offset) pair in both directions.

The Player screen shows the current chapter, position inside that chapter and
inside the whole book, a play/pause button flanked by 10-second skips, and
chapter previous/next below — all over the cover art, with progress through the
book as an arc around the rim. The chapter row only appears for books that
actually carry chapter marks.

Chapter marks come from `media.chapters` on the expanded item (`{ id, start,
end, title }`, seconds from the start of the book) and are stored alongside the
tracks, so chapter navigation works offline. "Previous" restarts the current
chapter when already more than three seconds into it and steps back otherwise,
which is the convention every audio player uses.

### Offline progress and sync

While playing, the position is written to Room every 10 seconds and immediately on
pause or stop: `itemId`, `currentTime` (global seconds), `duration`, `lastUpdate`
(epoch ms), `synced = false`.

`SyncWorker` runs under `NetworkType.CONNECTED` on app start, on pause, and hourly.
For each unsynced row it reads `GET /api/me/progress/{itemId}` first:

- server `lastUpdate` newer → the server wins; the local row is overwritten and
  nothing is pushed;
- otherwise → `PATCH /api/me/progress/{itemId}` with `{ currentTime, duration,
  progress }`, and the row is marked synced.

Opening a book while online does the same comparison before deciding where to
resume from.

The Home screen lists downloaded books straight out of Room, so it works with no
network at all.

---

## Pre-built APK from GitHub

`.github/workflows/release-apk.yml` builds a release APK and attaches it to a
GitHub Release, so the watch can be flashed without a build toolchain.

The APK holds no credentials — the server address and account are entered on the
watch — so the release asset is safe to hand to someone else, and there are no
build secrets to configure.

### Signing

Without a keystore the APK is signed with a throwaway debug key. It installs
fine, but the next release gets a different key and will not install over it —
you would have to uninstall first. To avoid that, generate a keystore once and
store it in the repository secrets:

```bash
keytool -genkeypair -v -keystore release.jks -alias wearabs \
        -keyalg RSA -keysize 2048 -validity 10000

base64 -w0 release.jks          # paste into ANDROID_KEYSTORE_BASE64
```

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The keystore above, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias, `wearabs` above |
| `ANDROID_KEY_PASSWORD` | Key password |

Keep `release.jks` somewhere safe and out of the repository — losing it means
future releases can no longer upgrade an existing install.

### Publishing a build

*Actions → Release APK → Run workflow*, enter a tag such as `v1.0.0`, and run it.
The workflow runs the unit tests, builds the release APK, and creates the Release
with `abs-wearos-player-v1.0.0.apk` attached. The version code is the workflow run
number, so each build installs over the previous one.

```bash
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r abs-wearos-player-v1.0.0.apk
```

---

## Verified against the upstream source

Every endpoint, field name and auth detail below was checked against
[audiobookshelf](https://github.com/advplyr/audiobookshelf) at tag `v2.36.1` and the
[official Android app](https://github.com/advplyr/audiobookshelf-app), not from
memory. Four things differ from a naive reading of the API and are worth knowing:

1. **Search does not return a flat list.** `GET /api/libraries/{id}/search` returns
   `{ book, narrators, tags, genres, series, authors }`, and the book matches are
   wrapped: `book[].libraryItem`. Title and author come from
   `libraryItem.media.metadata.title` / `.authorName`, and the length from
   `libraryItem.media.duration` (seconds).

2. **Tracks already carry `ino` in 2.36.1.** The server builds the track list by
   cloning each audio file and adding `startOffset`/`contentUrl`
   (`Book.getTracklist`), so the inode is right there. The official app still
   cross-references `media.audioFiles` by `metadata.path` to find it; this app uses
   `track.ino` and keeps that lookup only as a fallback for older payloads.

3. **`GET /api/me/progress/{itemId}` returns 404 when the server has no progress
   yet**, not an empty object — that is treated as "no server progress". `PATCH`
   replies `200` with an empty body, not JSON. `lastUpdate` is the row's server-side
   `updatedAt`; a client cannot set it.

4. **An unsatisfiable Range answers `500`, not `416`.** Express's `send` sets
   `416` plus `Content-Range: bytes */<size>`, and then Audiobookshelf's own
   `LibraryItemController.handleDownloadError` overwrites the status with `500`
   before the headers are flushed. This is not a corner case: it is exactly what
   a `.part` file that is already complete gets back when the download resumes.
   Checking for `416` — which the official Android app does — would loop until
   the retry limit, so this client keys off the `Content-Range` header instead.
   Confirmed against a live server: `Range: bytes=<filesize>-` returns `HTTP 500`,
   `Content-Range: bytes */<size>`, body `Download failed`.

5. **`isFinished: false` is destructive and is therefore omitted.** In
   `MediaProgress.applyProgressUpdate`, sending `isFinished: false` while the server
   has the book marked finished resets `currentTime` to 0 and drops the
   `currentTime` from the payload — which would throw away the very position being
   pushed. So `isFinished` is sent only when the book really is finished. The server
   un-finishes a book by itself when `currentTime` moves away from the end, and
   auto-finishes it when less than 10 seconds remain, so nothing is lost.

All of the above was then re-checked against a live 2.36.1 server: captured
payloads for 22 books — 1 to 500 tracks each, 1723 tracks in total — parse with
these exact DTOs, every track carries an `ino`, and the mapped offsets match the
server's cumulative `startOffset` values to within a microsecond.

Also confirmed: `POST /login` is rate limited, answers `401` for bad credentials,
and only returns a refresh token when `x-return-tokens: true` is sent
(`Auth.handleLoginSuccess`). Access tokens default to one hour and refresh tokens
to 30 days, both overridable server-side via `ACCESS_TOKEN_EXPIRY` and
`REFRESH_TOKEN_EXPIRY` (`TokenManager`).

## Interface

Built with Wear Compose Material 3 in the current expressive style:
`TransformingLazyColumn` for the scrolling lists, so items scale and morph at the
edges of the round display, and `EdgeButton` for each screen's primary action,
hugging the bottom rim.

- **Library** is a plain vertical text list of title and author. Cover
  thumbnails were tried and dropped: at watch size the artwork is too small to
  tell books apart, and scanning text is faster.
- **Book** shows the cover, the metadata, a progress bar once the book has been
  started, then Play and — below it, behind a confirmation dialog — Delete.
- **Book** and **Player** put the cover behind the content. The backdrop is
  deliberately decoded at ~96px and scaled up: bilinear filtering turns that into
  a soft wash for free, which is far kinder to a watch GPU than a real blur pass
  running behind a scrolling list. A radial scrim keeps text readable over any
  artwork.
- **Search** rows carry a small cover thumbnail beside title and author.

## Notes on dependency versions

The toolchain is pinned to AGP 8.13.x / Kotlin 2.2.21 rather than the newest
releases, because KSP — needed for Room — does not yet publish a build for Kotlin
2.4.x. Android lint reports the newer versions as available; that is expected.
`compileSdk`/`targetSdk` are 36, the API level Wear OS 6 is built on.

Two dependencies are held back deliberately, and both were checked rather than
assumed:

- **Wear Compose 1.5.6**, not 1.6.2. 1.6.2 pulls in Compose 1.12, which requires
  AGP 9.1+, and AGP 9 drops the separate Kotlin plugin — a toolchain migration
  with no payoff here, because 1.5.6 already ships every expressive component
  this app uses (`TransformingLazyColumn`, `EdgeButton`, `SurfaceTransformation`,
  `ResponsiveTransformationSpec`), verified by inspecting the 1.5.6 artifact.
- **Coil 3.3.0**, not 3.6.x. 3.6.x requires `compileSdk 37`, and 3.5.0 depends on
  kotlin-stdlib 2.4.0, whose metadata the Kotlin 2.2 compiler cannot read. 3.3.0
  is the newest release that depends on stdlib 2.2.x.
