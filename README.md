# radiotimes

A Fire TV app for watching live TV from a [tvtimes](https://github.com/issinoho/tvtimes)
server, using [tvdinner](https://github.com/issinoho/tvdinner) as its engine.

- **tvtimes** provides the channels, the guide, favourites and the watchlist,
  through the same export-token link tvdinner already understands
  (`tvtimess://host?token=…`).
- **tvdinner** runs unmodified inside the app (embedded CPython via
  [Chaquopy](https://chaquo.com/chaquopy/)). It handles playlist loading,
  XMLTV parsing and caching, now/next, and the tvtimes feeds.
- **The app** adds a 10-foot interface and plays streams with Media3/ExoPlayer.

## Status: Phase 1 — live TV

- **Setup:** enter the tvtimes link once (or send it from a computer, below).
- **Channel list:** groups down the left, channels with logo and what's on
  now, details of the focused channel on the right. The guide shows at once
  from cache and refreshes in the background when it's over 6 hours old.
- **Player:** fullscreen live TV with a now/next banner.
- **Guide:** ☰ opens a channels × time grid over the picture, which keeps
  playing in the corner; OK previews a channel, OK again shows the
  programme's full details.

Phase 0 measured the engine on a Fire TV Stick (Fire OS 8, 32-bit ARM):
channel list 0.4 s, guide from cache 0.4 s, fresh guide ~15 s for an 11.6 MB
XMLTV feed, first frame 2.1 s.

Roadmap: 0 probe ✓ → 1 live TV ✓ → 2 guide grid ✓ → **Plex** (via
tvdinner's Plex client) → tvtimes sync (favourites, watchlist, watch
reporting) → polish.

### Remote

| Where | Key | Does |
|---|---|---|
| Channel list | ↑ ↓ ← → | move between groups and channels |
| | OK | play |
| | ☰ (menu) | play the focused channel with the guide open |
| | Settings (foot of the groups) | change the tvtimes link |
| Player | ↑ / ↓, CH+ / CH− | next / previous channel in the group |
| | OK | show or hide what's on |
| | ☰ | guide, with the channel still playing in the corner |
| | Back | back to the list |
| Guide | ↑ ↓ ← → | move through channels and time (2 h at a time, 24 h ahead) |
| | OK | preview that channel in the corner |
| | OK again | full programme details |
| | OK in details | watch full screen |
| | Back / ☰ | details → guide → full screen |

## Install on a Fire TV Stick

1. On the Stick: *Settings → My Fire TV → Developer options* → turn on
   **ADB debugging** and **Install unknown apps** (on Fire OS 8, tap *About →
   Fire TV Stick* seven times to reveal Developer options). Note the Stick's IP
   under *About → Network*.
2. Download `radiotimes-debug-apk` from the latest
   [build run](../../actions/workflows/build.yml) and unzip it.
3. From a computer on the same network:

   ```sh
   adb connect <stick-ip>:5555        # accept the prompt on the TV
   adb install -r app-debug.apk
   ```

4. Open radiotimes from *Your Apps & Channels*. Typing the token with the
   remote is slow, so you can send your tvtimes link (Settings → Export
   feeds in tvtimes) from the computer instead, whether or not the app is
   already open:

   ```sh
   adb shell am start -n com.issinoho.radiotimes/.MainActivity \
     --es url 'tvtimess://your-host?token=YOUR_TOKEN'
   ```

   For a bug report, `adb logcat -s radiotimes python.stderr` shows the
   guide timings (`guide |`) and stream start-up (`play |`).

Tokens are masked in everything the app logs or shows.

**Not supported:** Fire TV devices running Vega OS (e.g. the Fire TV Stick 4K
Select) don't run Android apps.

## Development

```sh
git clone --recurse-submodules https://github.com/issinoho/radiotimes
# bridge tests (no Android needed)
pip install requests==2.32.3 pytest
PYTHONPATH=app/src/main/python:vendor/tvdinner/src python -m pytest -q python-tests
# APK (needs the Android SDK, JDK 17 and Python 3.11 on PATH)
./gradlew assembleDebug
```

To try it without a real tvtimes, run `python3 tools/fake_tvtimes.py` and
point an Android TV emulator at it (instructions in the file).
