# Changelog

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
