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

- **Setup:** a first-run wizard (Welcome → tvtimes → Plex → Ready).
  Scan the QR code on the TV and paste your links on your phone; or **Link Plex account** (enter the code at plex.tv/link: every
  server on the account is added, no tokens); or type them with the
  remote, or send them from a computer (below).
- **Channel list:** groups down the left, channels with logo and what's on
  now, details of the focused channel on the right. The guide shows at once
  from cache and refreshes in the background when it's over 6 hours old.
- **Player:** fullscreen live TV with a now/next banner.
- **Plex:** each Plex server is an entry in the group rail. Search, On
  Deck and libraries as poster grids (each library with **All**,
  **Recently Added** and **Recently Released** tabs), shows → seasons → episodes. OK on a
  movie or episode opens its details (summary, cast, director, genres,
  runtime, file quality, progress) with **Resume** / **Play from the
  start**. Plays the file directly with a seek bar; **Skip intro** /
  **Skip credits** (or **Next episode**) at Plex's markers; audio and
  subtitle tracks, including Plex's sidecar subtitle files, from the
  player's ⚙ and CC menus; progress reported back to Plex; the next
  episode starts when one ends.
- **Guide:** ☰ opens a channels × time grid over the picture, which keeps
  playing in the corner; OK previews a channel, OK again shows the
  programme's full details.

Phase 0 measured the engine on a Fire TV Stick (Fire OS 8, 32-bit ARM):
channel list 0.4 s, guide from cache 0.4 s, fresh guide ~15 s for an 11.6 MB
XMLTV feed, first frame 2.1 s.

### Roadmap

Done: 0 engine probe → 1 live TV → 2 guide over the picture → 3 Plex
(details, resume, skip intro/credits, tracks, search) → setup from a
phone and plex.tv/link.

Next:

- **Releases**: signed APKs on GitHub Releases, installable on a Fire TV
  with the Downloader app's short code.
- **tvtimes sync**: favourites, watchlist, watch reporting.
- **Find-and-approve pairing** *(needs a tvtimes change)*: tvtimes
  advertises itself on the LAN (mDNS); the TV finds it, shows a code,
  you approve it in tvtimes' Settings → Devices, and tvtimes issues the
  TV its own revocable export token.
- **"Send to Fire TV" from tvdinner** *(needs a tvdinner change)*:
  radiotimes advertises itself on the LAN; tvdinner's bookmarks screen
  pushes chosen tvtimes/Plex bookmarks to it in one keypress.
- **plex.tv linking in tvdinner itself**, so both apps share it.

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
| Plex | OK | open a list, or a movie/episode's details |
| | OK on Resume / Play | play |
| | Back | up a level |
| Plex player | OK | pause / play — or press the Skip / Next episode button when it shows |
| | ↑ ↓ ← → | show the seek bar and controls |
| | ⏪ ⏩ | back 10 s / forward 30 s |
| | Back | hide controls, then leave (progress is saved to Plex) |
| Guide | ↑ ↓ ← → | move through channels and time (2 h at a time, 24 h ahead) |
| | OK | preview that channel in the corner |
| | OK again | full programme details |
| | OK in details | watch full screen |
| | Back / ☰ | details → guide → full screen |

## Install on a Fire TV Stick

**With the Downloader app (no computer needed):**

1. On the Fire TV: *Settings → My Fire TV → Developer options* → turn on
   **Install unknown apps** for Downloader (on Fire OS 8, tap *About →
   Fire TV Stick* seven times first to reveal Developer options).
2. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
3. In Downloader, enter the radiotimes code (see the
   [tvdinner site](https://tvdinner.issinoho.com/#radiotimes)) or this
   address, which always fetches the latest release:
   `https://github.com/issinoho/radiotimes/releases/latest/download/radiotimes.apk`
4. Install, open radiotimes, and scan the QR code with your phone.

**With adb (developers):** turn on **ADB debugging** too, note the Stick's
IP under *About → Network*, then from a computer on the same network:

```sh
adb connect <stick-ip>:5555        # accept the prompt on the TV
adb install -r radiotimes.apk      # a release, or app-debug.apk from a CI run
```

4. Open radiotimes from *Your Apps & Channels*. Typing the token with the
   remote is slow, so you can send your tvtimes link (Settings → Export
   feeds in tvtimes) from the computer instead, whether or not the app is
   already open:

   ```sh
   adb shell am start -n com.issinoho.radiotimes/.MainActivity \
     --es url 'tvtimess://your-host?token=YOUR_TOKEN' \
     --es plex 'plex://host:32400?X-Plex-Token=TOKEN plex://other:32400?X-Plex-Token=TOKEN'
   ```

   (`--es plex` is optional; give one or more Plex links separated by
   spaces. The token is the one Plex Web shows under *Get Info → View XML*.)

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
