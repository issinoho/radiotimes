# CLAUDE.md

radiotimes is a Fire TV (Android) app: Compose UI + Media3 playback on top of
an **unmodified, pinned tvdinner** running in embedded CPython (Chaquopy),
fed by a **tvtimes** export-token link. `README.md` covers what it is and how
to install it; this file is about changing it safely.

## Build and test

```sh
PYTHONPATH=app/src/main/python:vendor/tvdinner/src python -m pytest -q python-tests
./gradlew assembleDebug
```

The Claude Code cloud container cannot reach Google's Maven repo, so the
APK is built only by GitHub Actions (`.github/workflows/build.yml`).
Push and read the job log; don't claim a Kotlin change compiles until CI
says so. The bridge tests do run locally.

## Hard rules

- **tvdinner is never modified from here.** It lives in `vendor/tvdinner`
  as a git submodule pinned to a release tag (currently `v1.46.4`).
  Upgrading = moving the submodule to a newer tag and running the bridge
  tests. If radiotimes needs something tvdinner lacks, the fix goes into
  tvdinner upstream as its own change, or into `bridge.py`, never a
  patched copy.
- **Only tvdinner's engine modules may be imported**: `m3u`, `epg`,
  `tvtimes`, `xtream`, `stalker`, `hdhomerun`, `vod`, `series`, `tmdb`,
  `history`, `schedule`, `favorites`, `playback_positions`. `cli`, `player`,
  `overlay`, `bookmarks_tui` need mpv/PIL/curses, which aren't packaged.
- **`app/src/main/python/bridge.py` is the only boundary.** Kotlin calls
  only bridge functions (via `Engine.call`); only bridge knows tvdinner's
  API. Bridge functions return a JSON string and never raise; errors come
  back as `{"error": "..."}`. Times cross as UTC epoch milliseconds.
- **Channels are keyed by stream URL**, never tvg-id (tvdinner's rule, and
  tvtimes' watchlist feed matches by URL).
- **Never log or display a token or upstream stream URL.** Use
  `redactTokens()` and `StreamResolver.redact()`. Xtream upstream URLs
  carry provider credentials in the path.
- **No tvtimes changes are assumed.** The app uses only `/api/exports/*`.

## Platform notes

- Fire OS 7 = API 28 (minSdk), Fire OS 8 = API 30. Older Sticks are 32-bit
  `armeabi-v7a`, which is why Chaquopy is on Python 3.12, not 3.13.
- Android has no tz database where CPython looks, so bridge.py avoids
  `zoneinfo`; Kotlin formats times in the device zone.
- tvtimes `/stream/<id>` is a 302 to the upstream URL. ExoPlayer picks
  HLS vs progressive from the URI it's given, so `StreamResolver` follows
  the redirect first, stopping as soon as an extension settles the type
  (IPTV providers often allow one connection per account).
- Vega OS Fire TV devices can't run APKs; out of scope.

## Device testing

There is no way to reach the Stick from here. The user sideloads the CI
APK and pastes `adb logcat -s radiotimes python.stderr`. Give complete
commands (package `com.issinoho.radiotimes`).

## Commits

Explain why, record what was verified and how.
