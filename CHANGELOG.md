# Changelog

## 0.6.0

- **Live channels reconnect by themselves.** A dropped or frozen stream
  is retried automatically (after 1, 2, 4, 8, 15 s, then every 30 s, the
  stream re-resolved each time) while the banner says "Lost the stream.
  Reconnecting…"; it gives up after 10 minutes without a picture. Covers
  network errors, a stream whose connection closes (previously the
  picture just froze), no progress for 20 s, and falling behind a live
  window (rejoins live). Formats the device can't decode still fail at
  once, since retrying can't help.
- **Plex files the device can't decode play anyway.** If the TV can't
  decode a file as it is (an HEVC/10-bit video, an audio format it lacks),
  radiotimes switches to a version converted on the fly by your Plex
  server (H.264/AAC), at the same position, with seeking, resume and
  subtitles working as before. The server stops converting when you
  leave.
- **tvtimes favourites and watchlist**: a "★ Favourites" group with the
  channels starred on your tvtimes account, and a "Watchlist" group with
  the upcoming airings flagged there ("Today 21:00 · Title"), soonest
  first. OK tunes the channel. Refreshed every 15 minutes.
- **Previous channel**: ◀ (left) in the live player flips back to the
  channel you were watching before (the remote's Last key too, where it
  has one); the banner shows "◀ back to …". Channels raced past while
  zapping don't count.
- **Update notice**: when a newer radiotimes is released, the channel
  list's header says "Update X available · Downloader code 8716486".
  It checks GitHub at most every 12 hours; the update itself is still
  done through Downloader.

## 0.5.1

- **Subtitles on/off in the Plex ☰ panel**: a "Subtitles: On / Off"
  button, shown whenever the item has subtitles (in the file or attached
  in Plex). Turning them back on picks a track in the device's language.
  Attaching subtitles online always switches them on.
- **Everything in the ☰ panel fits on screen**: all three buttons with the
  longest labels, and the subtitle results (the panel drops the summary
  and credits while you choose). The player's own controls no longer pop
  up by themselves (on an error) and take focus from the panel.

## 0.5.0

- **Picture size**: Fit, Zoom (fills the screen, crops the edges -- for
  4:3 channels or letterboxed films) or Stretch. Hold OK to cycle, in live
  TV and Plex; remembered between sessions. Also a button in Plex's ☰
  panel.
- **Online subtitles for Plex**: ☰ → Find subtitles online lists results
  from your Plex server's own subtitle search (OpenSubtitles; no account
  or API key needed). Pick one and Plex downloads and attaches it to the
  item (so your other Plex apps have it too) and it switches on where you
  are.
- **Dark channel logos** (TCM's black-on-transparent one) are now shown
  inverted or on a light plate instead of disappearing.
- **Play/pause, ⏩ and ⏪ work in the Plex player straight away**, without
  pressing OK first.

## 0.4.2

- **Plex player: programme information.** OK while watching shows the
  controls with a banner: show, season and episode, title, year,
  certificate, rating, genres, a short summary, position, time left and
  when it ends. ☰ (or Info) opens the full details over the picture:
  complete summary, director, cast, studio and file quality. Back or ☰
  closes it. A second OK pauses (the controller's play/pause has focus).

## 0.4.1

- **Fix: phone setup page failed with ERR_TOO_MANY_RETRIES** and could
  close radiotimes. A connection that sent nothing (phone browsers open
  spare ones) timed out with an uncaught error that crashed the app, and
  Chrome's attempt at https:// got a garbled reply. The setup server now
  never lets a connection crash the app, refuses https at once so the
  browser falls back to http, reads requests as bytes with size limits,
  closes connections without cutting replies short, and logs each request
  (`setup |` in logcat, without the code or links).

## 0.4.0

- **First-run setup wizard**: Welcome → tvtimes → Plex (optional) →
  Ready. The phone setup page runs throughout, so links sent from a phone
  show up as each step completes ("Connected ✓ 552 channels", "Found ✓
  SmithPlex, docker-nuc"); typing with the remote is still offered.
  Settings keeps the one-page setup screen for later changes.

## 0.3.0

First release.

- **Live TV** from a tvtimes account: channel groups, logos, now/next, a
  details panel; fullscreen player with channel up/down and a now/next
  banner; HDHomeRun DVB channels (audio-description tracks skipped, MPEG
  Layer II audio decoded).
- **Guide**: ☰ opens a channels × time grid over the picture, which keeps
  playing in the corner; OK previews a channel, OK again shows full
  programme details.
- **Plex**: any number of servers; search, On Deck, libraries (All /
  Recently Added / Recently Released), shows, seasons, episodes; a details page with Resume / Play from the start;
  skip intro / skip credits / next episode; audio and subtitle tracks
  (including Plex's sidecar subtitles); progress reported back to Plex.
- **Setup without typing tokens**: scan the QR code on the TV and paste
  links on your phone, or Link Plex account at plex.tv/link.

## 0.2.0

Unreleased development builds (Phase 1: live TV).
