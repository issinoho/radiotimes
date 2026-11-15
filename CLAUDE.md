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

The Claude Code cloud container cannot reach Google's Maven repo, so there
the APK is built only by GitHub Actions (`.github/workflows/build.yml`):
push and read the job log, and don't claim a Kotlin change compiles until
CI says so. On a machine with the Android SDK and JDK 17 it builds locally,
and `tools/fake_tvtimes.py` plus an Android TV emulator (API 36 TV image is
x86 with ARM translation, so it runs the armeabi-v7a build like older
Sticks) lets UI changes be driven with `adb shell input keyevent` and
checked with `adb exec-out screencap -p`. The user's installs still come
from CI.

## Hard rules

- **tvdinner is never modified from here.** It lives in `vendor/tvdinner`
  as a git submodule pinned to a release tag (currently `v1.46.4`).
  Upgrading = moving the submodule to a newer tag and running the bridge
  tests. If radiotimes needs something tvdinner lacks, the fix goes into
  tvdinner upstream as its own change, or into `bridge.py`, never a
  patched copy.
- **Only tvdinner's engine modules may be imported**: `m3u`, `epg`,
  `tvtimes`, `plex`, `xtream`, `stalker`, `hdhomerun`, `vod`, `series`,
  `tmdb`, `history`, `schedule`, `favorites`, `playback_positions`. `cli`, `player`,
  `overlay`, `bookmarks_tui` need mpv/PIL/curses, which aren't packaged.
- **`app/src/main/python/bridge.py` is the only boundary.** Kotlin calls
  only bridge functions (via `Engine.call`); only bridge knows tvdinner's
  API. Bridge functions return a JSON string and never raise; errors come
  back as `{"error": "..."}`. Times cross as UTC epoch milliseconds.
- **Two engine threads**: live TV calls go through `Engine.call`, `plex_*`
  calls through `Engine.callPlex`. bridge.py's TV state and Plex state
  must stay disjoint, since the two can run at once (so Plex doesn't
  wait out a ~15 s guide refresh on a Stick).
- **Channels are keyed by stream URL**, never tvg-id (tvdinner's rule, and
  tvtimes' watchlist feed matches by URL).
- **Never log or display a token or upstream stream URL.** Use
  `redactTokens()` and `StreamResolver.redact()` in Kotlin; bridge.py
  redacts its errors, tracebacks and tvdinner's `logging` output (which
  quotes URLs verbatim and reaches logcat via stderr). Xtream upstream URLs
  carry provider credentials in the path. Plex stream and artwork URLs
  carry `X-Plex-Token` (they must, for ExoPlayer and Coil); never log them.
  Plex links reach the app via Settings or `--es plex`, never the repo.
- **No tvtimes changes are assumed.** The app uses only `/api/exports/*`.

## Platform notes

- Fire OS 7 = API 28 (minSdk), Fire OS 8 = API 30. Older Sticks are 32-bit
  `armeabi-v7a`, which is why Chaquopy is on Python 3.11: no Chaquopy
  3.12+ target ships that ABI (checked on Maven Central).
- Android has no tz database where CPython looks, so bridge.py avoids
  `zoneinfo`; Kotlin formats times in the device zone.
- tvtimes `/stream/<id>` is a 302 to the upstream URL. ExoPlayer picks
  HLS vs progressive from the URI it's given, so `StreamResolver` follows
  the redirect first, stopping as soon as an extension settles the type
  (IPTV providers often allow one connection per account).
- Vega OS Fire TV devices can't run APKs; out of scope.

## UI notes

- 1080p at TV density is only 960dp wide: budget columns against that.
- Don't use Compose's `focusRestorer` on lazy lists (Compose 1.7 crashes
  with "Release should only be called once"); use `focusProperties { enter }`.
- Don't move focus into an overlay and back (Compose drops it on the
  first item in view when the overlay goes, before any restore runs):
  keep focus where it is, draw the overlay, and gate keys in the
  activity, as the guide's info panel does.
- Never start focus on a text field: selecting it opens Fire TV's
  full-screen keyboard, which Back doesn't reliably dismiss.

## Device testing

There is no way to reach the Stick from here. The user sideloads the CI
APK and pastes `adb logcat -s radiotimes python.stderr`. Give complete
commands (package `com.issinoho.radiotimes`).

## Commits

Explain why, record what was verified and how.
