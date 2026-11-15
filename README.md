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

## Status: Phase 0 — engine probe

This build is not the app yet; it's a measurement. It answers two questions on
real hardware before any interface work starts:

1. Is tvdinner's Python XMLTV parse fast enough on a Fire TV Stick, cold and
   from its cache?
2. Does ExoPlayer on the Stick play what tvtimes hands it?

Roadmap: 0 probe → 1 live TV (setup, channels, player) → 2 guide grid →
3 tvtimes sync (favourites, watchlist, watch reporting) → 4 polish.

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

4. Run the probe with your tvtimes link (Settings → Export feeds in tvtimes),
   which saves typing it with the remote:

   ```sh
   adb shell am start -n com.issinoho.radiotimes/.MainActivity \
     --es url 'tvtimess://your-host?token=YOUR_TOKEN' --ez autorun true
   adb logcat -s radiotimes python.stderr
   ```

   Then press **Play** to try the first channel; the `play |` lines in logcat
   give the time to first frame and the decoded format.

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
