"""radiotimes' only door into tvdinner.

The Kotlin side calls nothing in Python except the functions here, and
nothing here is called by anything except Kotlin (and the tests). That
keeps one place that knows tvdinner's internals: when the pinned tvdinner
in vendor/tvdinner is bumped and something it exposes changes shape, this
file and python-tests/ are what break, not the UI.

Rules this module keeps:

* **Only tvdinner's engine modules are imported.** `cli`, `player`,
  `overlay` and `bookmarks_tui` pull in mpv, PIL or curses, none of which
  exist on a Fire TV and none of which are packaged. Everything imported
  below needs only `requests` and the standard library.
* **Every public function returns a JSON string and never raises.** A
  Python exception crossing Chaquopy arrives in Kotlin as a PyException
  with a traceback for a message, which is no use on a TV screen. Errors
  come back as `{"error": "..."}` instead, which the UI can show.
* **Times cross the boundary as epoch milliseconds (UTC).** Kotlin formats
  them in the device's zone. Nothing here depends on Python's zoneinfo
  finding a tz database, which Android does not have where CPython looks.
* **Channels are identified by stream URL**, never tvg-id -- tvdinner's
  rule (tvg-id is not unique; tvtimes' East/West fan-out shares one) and
  the key tvtimes' watchlist feed uses too.

State lives at module level because there is exactly one engine per
process and the Kotlin side serialises every call onto one thread.
"""

from __future__ import annotations

import json
import logging
import re
import sys
import time
import urllib.parse
import traceback
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable

import requests
from tvdinner import epg as tv_epg
from tvdinner import plex as tv_plex
from tvdinner.m3u import Channel, Playlist, load_playlist
from tvdinner.tvtimes import (
    TvtimesFeed,
    fetch_tvtimes_favourites,
    fetch_tvtimes_watchlist,
    parse_tvtimes_url,
    tvtimes_epg_url,
    tvtimes_playlist_url,
)

# Case-insensitive: tvtimes uses `token=`, Plex `X-Plex-Token=`.
_TOKEN_RE = re.compile(r"(token=)[^&\s'\":]+", re.IGNORECASE)


def redact(text: str) -> str:
    """Mask `token=` values. tvtimes stream, playlist and EPG URLs all carry
    the export token, and tvdinner's own warnings and requests' exception
    messages quote those URLs verbatim."""
    return _TOKEN_RE.sub(r"\1…", text)


class _CurrentStderrHandler(logging.StreamHandler):
    """Writes to whatever sys.stderr is now, not the one at import time."""

    @property  # type: ignore[override]
    def stream(self):  # noqa: ANN201
        return sys.stderr

    @stream.setter
    def stream(self, _value: object) -> None:
        pass


class _RedactingFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        return redact(super().format(record))


def _install_log_redaction() -> None:
    # tvdinner logs through `logging` with no handler configured, so its
    # warnings would go out via logging.lastResort -- unredacted, to stderr,
    # which Chaquopy forwards to logcat, which users paste into bug reports.
    root = logging.getLogger()
    if not any(isinstance(h.formatter, _RedactingFormatter) for h in root.handlers):
        handler = _CurrentStderrHandler()
        handler.setFormatter(_RedactingFormatter("%(name)s: %(message)s"))
        root.addHandler(handler)
        root.setLevel(logging.WARNING)


_install_log_redaction()

_feed: TvtimesFeed | None = None
_cache_dir: Path | None = None
_playlist: Playlist | None = None
_epg: tv_epg.Epg | None = None


# --------------------------------------------------------------------------
# helpers


def _ok(payload: Any) -> str:
    return json.dumps(payload)


def _err(message: str) -> str:
    return json.dumps({"error": redact(message)})


def _guarded(fn: Callable[..., str]) -> Callable[..., str]:
    """Turn any unexpected exception into an `{"error": ...}` result.

    The traceback goes to stderr, which Chaquopy forwards to logcat under
    the `python.stderr` tag -- so a bug is still diagnosable from
    `adb logcat` without the UI having to render a stack trace.
    """

    def wrapper(*args: Any, **kwargs: Any) -> str:
        try:
            return fn(*args, **kwargs)
        except Exception as exc:  # noqa: BLE001 -- the whole point
            sys.stderr.write(redact(traceback.format_exc()))
            return _err(redact(f"{type(exc).__name__}: {exc}"))

    wrapper.__name__ = fn.__name__
    wrapper.__doc__ = fn.__doc__
    return wrapper


def _ms(moment: datetime) -> int:
    return int(moment.timestamp() * 1000)


def _max_rss_kb() -> int | None:
    try:
        import resource

        # Kilobytes on Linux, which is what Android is.
        return int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    except Exception:  # noqa: BLE001 -- informational only
        return None


def _epg_cache_dir() -> Path | None:
    if _cache_dir is None:
        return None
    path = _cache_dir / "epg"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _channel_json(channel: Channel) -> dict[str, Any]:
    return {
        "name": channel.name,
        "url": channel.url,
        "tvg_id": channel.tvg_id,
        "logo": channel.tvg_logo,
        "group": channel.group_title,
    }


def _programme_json(programme: tv_epg.Programme | None, display: tv_epg.EpgDisplay, channel: Channel) -> dict[str, Any] | None:
    if programme is None:
        return None
    shift = display.shift_for(channel.name)
    return {
        "title": programme.title,
        "start": _ms(programme.start + shift),
        "stop": _ms(programme.stop + shift),
        "description": programme.description,
        "category": programme.category,
        "poster": programme.poster_url,
        "year": programme.year,
    }


def _display() -> tv_epg.EpgDisplay:
    # tvtimes shifts every channel on export (its generator-info-name is in
    # tvdinner's PRE_CORRECTED_GENERATORS), so no correction is applied
    # here. Passing the flag through rather than assuming it keeps this
    # right if the feed is ever something else.
    return tv_epg.EpgDisplay(guide_already_corrected=bool(_epg and _epg.times_already_corrected))


def _require_feed() -> TvtimesFeed:
    if _feed is None:
        raise RuntimeError("not configured: call configure() first")
    return _feed


# --------------------------------------------------------------------------
# public surface


@_guarded
def configure(tvtimes_url: str, cache_dir: str) -> str:
    """Point the engine at a tvtimes export feed.

    `tvtimes_url` is the same `tvtimes://host?token=...` (or `tvtimess://`
    for https) link tvdinner accepts. `cache_dir` is the app's cache
    directory -- tvdinner's default EPG cache location is under `~`, which
    is not somewhere an Android app should write.
    """
    global _feed, _cache_dir, _playlist, _epg
    feed = parse_tvtimes_url(tvtimes_url.strip())
    if feed is None:
        return _err("Not a tvtimes link. Expected tvtimes://host?token=... or tvtimess://host?token=...")
    _feed = feed
    _cache_dir = Path(cache_dir)
    _playlist = None
    _epg = None
    return _ok({"base_url": feed.base_url})


@_guarded
def load_channels() -> str:
    """Fetch the line-up. Returns a list of channels in tvtimes' order."""
    global _playlist
    feed = _require_feed()
    playlist = load_playlist(tvtimes_playlist_url(feed))
    if playlist is None:
        return _err(f"Could not load the channel list from {feed.base_url}")
    _playlist = playlist
    return _ok([_channel_json(c) for c in playlist.channels])


# How load_guide's modes map onto tvdinner's cache age. "cached" takes any
# copy on disk however old -- a day-old guide still has tonight in it, and
# showing it at once beats a blank screen for the ~15 s a Fire TV Stick
# needs to fetch and parse a fresh one. "refresh" forces the network.
_GUIDE_MAX_AGE = {
    "auto": tv_epg.DEFAULT_EPG_CACHE_MAX_AGE,
    "cached": timedelta(days=3650),
    "refresh": timedelta(0),
}


def _guide_cache_file() -> Path | None:
    epg_dir = _epg_cache_dir()
    if epg_dir is None or _feed is None:
        return None
    return tv_epg.cache_path_for(epg_dir, tvtimes_epg_url(_feed))


@_guarded
def load_guide(mode: str = "auto") -> str:
    """Fetch and parse the XMLTV guide for the loaded line-up.

    `mode` is "auto" (tvdinner's normal 24 h cache), "cached" (whatever is
    on disk, however old, and never the network: `{"loaded": false}` if
    there is nothing) or "refresh" (always the network; tvdinner falls back
    to the stale copy if that fails, reported as `network_failed`).

    The result carries where the guide came from and how long each part
    took, measured through tvdinner's public download-progress callback:
    `download_s` runs to the last byte received, `parse_s` from there to the
    end (parse plus writing tvdinner's parsed cache).

    Uses tvtimes' EPG URL derived from the link the user typed, not the
    playlist's `url-tvg=` header, for the reason tvdinner's tvtimes module
    gives: that header carries tvtimes' configured public origin, which need
    not be reachable from the TV.
    """
    global _epg
    feed = _require_feed()
    if _playlist is None:
        return _err("Load channels before the guide")
    if mode not in _GUIDE_MAX_AGE:
        return _err(f"Unknown guide mode {mode!r}")
    raw = _guide_cache_file()
    if mode == "cached" and (raw is None or not raw.is_file()):
        return _ok({"loaded": False})

    received = {"bytes": 0, "at": None}

    def on_progress(done: int, _total: int | None) -> None:
        received["bytes"] = done
        received["at"] = time.monotonic()

    started = time.monotonic()
    epg = tv_epg.load_epg_for_playlist(
        _playlist,
        override=tvtimes_epg_url(feed),
        cache_dir=_epg_cache_dir(),
        max_age=_GUIDE_MAX_AGE[mode],
        on_progress=on_progress,
    )
    finished = time.monotonic()
    if epg is None:
        return _err(f"Could not load the guide from {feed.base_url}")
    _epg = epg
    fetched = received["at"] is not None
    result: dict[str, Any] = {
        "loaded": True,
        "epg_channels": len(epg.channels),
        "programmes": sum(len(p) for p in epg.programmes.values()),
        "pre_corrected": epg.times_already_corrected,
        "source": "network" if fetched else "cache",
        "network_failed": mode == "refresh" and not fetched,
        "total_s": round(finished - started, 2),
        "rss_kb": _max_rss_kb(),
        "updated": _ms(datetime.fromtimestamp(raw.stat().st_mtime, tz=timezone.utc)) if raw and raw.is_file() else None,
    }
    if fetched:
        result["bytes"] = received["bytes"]
        result["download_s"] = round(received["at"] - started, 2)
        result["parse_s"] = round(finished - received["at"], 2)
    return _ok(result)


@_guarded
def now_next(at_ms: int | None = None) -> str:
    """`{stream_url: {"now": programme|null, "next": programme|null}}` for
    every loaded channel that has guide data."""
    if _playlist is None or _epg is None:
        return _ok({})
    at = datetime.fromtimestamp(at_ms / 1000, tz=timezone.utc) if at_ms is not None else datetime.now(timezone.utc)
    display = _display()
    out: dict[str, Any] = {}
    for channel in _playlist.channels:
        now, upcoming = display.now_and_next(
            _epg, channel.tvg_id, at, channel_name=channel.name, match_name=channel.tvg_name or channel.name
        )
        if now is None and upcoming is None:
            continue
        out[channel.url] = {
            "now": _programme_json(now, display, channel),
            "next": _programme_json(upcoming, display, channel),
        }
    return _ok(out)


@_guarded
def schedule(stream_url: str, from_ms: int, to_ms: int) -> str:
    """Every programme on one channel overlapping `[from_ms, to_ms)`."""
    if _playlist is None or _epg is None:
        return _ok([])
    channel = next((c for c in _playlist.channels if c.url == stream_url), None)
    if channel is None:
        return _err("Unknown channel")
    display = _display()
    shift = display.shift_for(channel.name)
    lo = datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc) - shift
    hi = datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc) - shift
    progs = _epg.schedule_for(channel.tvg_id, channel.tvg_name or channel.name)
    return _ok([_programme_json(p, display, channel) for p in progs if p.stop > lo and p.start < hi])


@_guarded
def favourites() -> str:
    """Channel names favourited on the tvtimes account."""
    names, error = fetch_tvtimes_favourites(_require_feed())
    if error:
        return _err(error)
    return _ok(sorted(names))


@_guarded
def watchlist() -> str:
    """Upcoming watchlisted airings, each keyed to a channel by stream URL."""
    entries, error = fetch_tvtimes_watchlist(_require_feed())
    if error:
        return _err(error)
    return _ok(
        [
            {
                "channel_url": e.channel_url,
                "channel_name": e.channel_name,
                "title": e.title,
                "start": _ms(e.start),
                "stop": _ms(e.stop),
            }
            for e in entries
        ]
    )


# --------------------------------------------------------------------------
# Plex
#
# Any number of servers, each a `plex://host:port?X-Plex-Token=...` link
# (tvdinner's own format). Kotlin refers to a server by its index in the
# list given to plex_configure, and to a node by the JSON this module gave
# it. Stream and artwork URLs carry the token -- they have to, for
# ExoPlayer and the image loader to fetch them -- so Kotlin must never log
# them; everything this module logs or returns as an error is redacted.

_plex: list[tv_plex.PlexCreds] = []
_plex_client_id: str | None = None


def _plex_server(index: int) -> tv_plex.PlexCreds:
    if not 0 <= index < len(_plex):
        raise RuntimeError("Unknown Plex server")
    return _plex[index]


def _plex_small(url: str | None, width: int, height: int) -> str | None:
    """The same Plex image, resized by the server's photo transcoder. A
    poster from tvdinner's node builders is the full-size original (~750 KB
    on a real server, ~1.2 s each): a grid of 60 crawled in on the TV.
    At grid size it's a few tens of KB."""
    if not url:
        return None
    parts = urllib.parse.urlsplit(url)
    query = urllib.parse.parse_qs(parts.query)
    token = (query.pop("X-Plex-Token", [None]))[0]
    if not token or not parts.path.startswith("/library/"):
        return url
    inner = parts.path + ("?" + urllib.parse.urlencode(query, doseq=True) if query else "")
    params = urllib.parse.urlencode(
        {"width": width, "height": height, "minSize": 1, "upscale": 1, "url": inner, "X-Plex-Token": token}
    )
    return f"{parts.scheme}://{parts.netloc}/photo/:/transcode?{params}"


def _node_json(node: tv_plex.PlexNode) -> dict[str, Any]:
    episode = node.kind == "episode"
    return {
        "rating_key": node.rating_key,
        "title": node.title,
        "kind": node.kind,
        "container": node.container,
        "subtitle": node.subtitle,
        # Episode stills are 16:9 (the episode list); everything else a 2:3 poster.
        "thumb": _plex_small(node.thumb_url, 320, 180) if episode else _plex_small(node.thumb_url, 240, 360),
        "watched": node.watched,
        "progress": node.watch_progress,
        "year": node.year,
        "series_title": node.series_title,
        "season_thumb": _plex_small(node.season_thumb_url, 240, 360),
    }


def _node_from(data: dict[str, Any]) -> tv_plex.PlexNode:
    # Dispatch in tvdinner only reads rating_key and kind; title is used in
    # its error messages.
    return tv_plex.PlexNode(rating_key=str(data["rating_key"]), title=str(data.get("title", "")), kind=str(data["kind"]))


@_guarded
def plex_configure(links_json: str, files_dir: str) -> str:
    """Set the Plex servers from a JSON list of links (JSON rather than a
    Java list proxy from Chaquopy). Returns one entry per link, in order:
    `{"base_url", "name"}` for a usable one, `{"error"}` for a malformed
    one (which keeps its slot so indexes stay aligned). `files_dir` holds
    the persistent Plex client id, so this device stays one device in
    Plex's dashboard across launches."""
    global _plex, _plex_client_id
    links = json.loads(links_json)
    _plex_client_id = tv_plex.load_plex_client_id(Path(files_dir) / "plex_client_id.json")
    servers: list[tv_plex.PlexCreds] = []
    out: list[dict[str, Any]] = []
    for link in links:
        creds = tv_plex.parse_plex_url(str(link).strip())
        if creds is None:
            servers.append(tv_plex.PlexCreds(base_url="", token=""))
            out.append({"error": "Not a Plex link. Expected plex://host:port?X-Plex-Token=..."})
            continue
        servers.append(creds)
        out.append({"base_url": creds.base_url, "name": _plex_name(creds)})
    _plex = servers
    return _ok(out)


def _plex_name(creds: tv_plex.PlexCreds) -> str:
    """The server's own friendly name, or its host if it can't be reached
    right now (tvdinner has no call for this; it's one GET of `/`)."""
    try:

        r = requests.get(
            f"{creds.base_url}/", headers={"Accept": "application/json", "X-Plex-Token": creds.token}, timeout=5
        )
        r.raise_for_status()
        name = (r.json().get("MediaContainer") or {}).get("friendlyName")
        if name:
            return str(name)
    except Exception:  # noqa: BLE001 -- a name is cosmetic
        pass
    return creds.base_url.split("://", 1)[-1]


@_guarded
def plex_children(server: int, node_json: str | None = None) -> str:
    """The libraries (plus tvdinner's "On Deck" row) when `node_json` is
    None, else the children of that container node."""
    creds = _plex_server(server)
    node = _node_from(json.loads(node_json)) if node_json else None
    nodes, error = tv_plex.list_plex_node_children(creds, node)
    if error:
        return _err(error)
    return _ok([_node_json(n) for n in nodes])


# Plex's own type numbers for /library/sections/<key>/all?type=...
_PLEX_TYPE_MOVIE = "1"
_PLEX_TYPE_EPISODE = "4"
_LIBRARY_VIEW_SORT = {"recently_added": "addedAt:desc", "recently_released": "originallyAvailableAt:desc"}
_LIBRARY_VIEW_SIZE = "60"


@_guarded
def plex_library_view(server: int, node_json: str, view: str) -> str:
    """A library's "all" listing (tvdinner's own), or its 60 most recently
    added / released items. Movie libraries list movies; TV libraries list
    episodes, which is what "recent" means there (a show added years ago
    gets new episodes).

    tvdinner has no call for the recent views, so this uses its private
    _api_get and node builders -- read, not modified; the pinned submodule
    and these tests catch any change on upgrade."""
    node = _node_from(json.loads(node_json))
    creds = _plex_server(server)
    if view == "all":
        nodes, error = tv_plex.list_plex_node_children(creds, node)
        return _err(error) if error else _ok([_node_json(n) for n in nodes])
    if view not in _LIBRARY_VIEW_SORT or node.kind not in ("library_movie", "library_show"):
        return _err(f"No {view!r} view for this")
    movies = node.kind == "library_movie"
    params = {
        "type": _PLEX_TYPE_MOVIE if movies else _PLEX_TYPE_EPISODE,
        "sort": _LIBRARY_VIEW_SORT[view],
        "X-Plex-Container-Start": "0",
        "X-Plex-Container-Size": _LIBRARY_VIEW_SIZE,
    }
    try:
        result = tv_plex._api_get(creds, f"/library/sections/{node.rating_key}/all", params=params)
    except tv_plex._PlexApiError as exc:
        return _err(str(exc))
    build = tv_plex._movie_node if movies else tv_plex._episode_node
    items = (result.get("MediaContainer") or {}).get("Metadata") or []
    nodes = [n for n in (build(creds, item) for item in items if isinstance(item, dict)) if n is not None]
    return _ok([_node_json(n) for n in nodes])


@_guarded
def plex_search(server: int, query: str) -> str:
    nodes, error = tv_plex.search_plex(_plex_server(server), query)
    if error:
        return _err(error)
    return _ok([_node_json(n) for n in nodes])


@_guarded
def plex_resolve(server: int, node_json: str) -> str:
    """A movie or episode to what the player needs: the direct-play URL,
    the details for its info screen, where to resume, and the keys for
    finding the next episode."""
    item, error = tv_plex.resolve_plex_playable(_plex_server(server), _node_from(json.loads(node_json)))
    if error or item is None:
        return _err(error or "Nothing to play")

    def marker(m: Any) -> dict[str, float] | None:
        return {"start": m.start_seconds, "end": m.end_seconds} if m else None

    creds = _plex_server(server)
    return _ok(
        {
            **_plex_extras(creds, str(item.rating_key)),
            "title": item.title,
            "url": item.url,
            "poster": item.poster_url,
            "backdrop": item.backdrop_url,
            "year": item.year,
            "rating": item.rating,
            "description": item.description,
            "director": item.director,
            "resume_seconds": item.resume_seconds,
            "rating_key": item.rating_key,
            "series_title": item.series_title,
            "parent_rating_key": item.plex_parent_rating_key,
            "grandparent_rating_key": item.plex_grandparent_rating_key,
            "intro": marker(item.intro_marker),
            "credits": marker(item.credits_marker),
        }
    )


@_guarded
def plex_next_episode(server: int, rating_key: str, parent_rating_key: str, grandparent_rating_key: str | None) -> str:
    """The episode after this one (into the next season if need be), or null."""
    node = tv_plex.find_next_episode(_plex_server(server), rating_key, parent_rating_key, grandparent_rating_key)
    return _ok(_node_json(node) if node else None)


@_guarded
def plex_report(server: int, session_id: str, rating_key: str, state: str, position_s: float, duration_s: float) -> str:
    """Tell Plex what's playing ("playing"/"paused"/"stopped"), which is
    what moves its resume point and marks things watched. Failure is
    reported but harmless."""
    ok, error = tv_plex.report_plex_timeline(
        _plex_server(server), _plex_client_id or "radiotimes", session_id, rating_key, state, position_s, duration_s
    )
    return _ok({"ok": ok}) if ok else _err(error or "report failed")


def _video_label(media: dict) -> str | None:
    """"1080p H264", "4K HEVC", "SD H264"."""
    res = str(media.get("videoResolution") or "")
    res = f"{res}p" if res.isdigit() else res.upper()
    codec = str(media.get("videoCodec") or "").upper()
    return " ".join(x for x in (res, codec) if x) or None


@_guarded
def plex_transcode_url(server: int, rating_key: str, session: str) -> str:
    """For a file this device can't decode: Plex's universal transcoder as
    HLS (H.264/AAC), the way Plex's own apps fall back. `{"url"}` -- the
    master playlist, token included (the session's own segment URLs need
    none). Identifies as the Chrome platform, whose built-in client profile
    makes Plex convert anything a browser can't play; the playlist covers
    the whole item (Plex converts segments on demand), so the player seeks
    in it normally. Not in tvdinner."""
    creds = _plex_server(server)
    params = {
        "path": f"/library/metadata/{rating_key}",
        "mediaIndex": "0",
        "partIndex": "0",
        "protocol": "hls",
        "fastSeek": "1",
        "copyts": "1",
        "offset": "0",
        "directPlay": "0",
        "directStream": "1",
        "videoQuality": "100",
        "videoResolution": "1920x1080",
        "maxVideoBitrate": "8000",
        "session": session,
        "X-Plex-Session-Identifier": session,
        "X-Plex-Platform": "Chrome",
        "X-Plex-Product": "radiotimes",
        "X-Plex-Client-Identifier": _plex_client_id or "radiotimes",
        "X-Plex-Token": creds.token,
    }
    query = urllib.parse.urlencode(params)
    # Plex answers start.m3u8 with 400 for a session it hasn't made a
    # decision for: ask for the decision first, as its own clients do.
    r = requests.get(
        f"{creds.base_url}/video/:/transcode/universal/decision?{query}",
        headers={"Accept": "application/json"},
        timeout=20,
    )
    r.raise_for_status()
    return _ok({"url": f"{creds.base_url}/video/:/transcode/universal/start.m3u8?{query}"})


@_guarded
def plex_transcode_stop(server: int, session: str) -> str:
    """End a transcode session so the server stops converting."""
    creds = _plex_server(server)
    r = requests.get(
        f"{creds.base_url}/video/:/transcode/universal/stop",
        params={"session": session},
        headers={"X-Plex-Token": creds.token},
        timeout=10,
    )
    r.raise_for_status()
    return _ok({"ok": True})


@_guarded
def plex_subtitle_search(server: int, rating_key: str, language: str) -> str:
    """Online subtitles for an item, from the Plex server's own subtitle
    search (its built-in OpenSubtitles provider -- what Plex's apps call
    "Search for subtitles"): `[{"key", "title", "provider", "language",
    "codec", "hearing_impaired"}]`, in Plex's order. No API key: the server
    does the searching. Not in tvdinner; one GET."""
    creds = _plex_server(server)
    r = requests.get(
        f"{creds.base_url}/library/metadata/{rating_key}/subtitles",
        params={"language": language, "hearingImpaired": "0", "forced": "0"},
        headers={"Accept": "application/json", "X-Plex-Token": creds.token},
        timeout=30,
    )
    r.raise_for_status()
    streams = (r.json().get("MediaContainer") or {}).get("Stream") or []
    return _ok(
        [
            {
                "key": s["key"],
                "title": s.get("title") or s.get("displayTitle") or "Subtitles",
                "provider": s.get("providerTitle"),
                "language": s.get("languageCode") or language,
                "codec": s.get("codec") or "srt",
                "hearing_impaired": bool(s.get("hearingImpaired")),
            }
            for s in streams
            if isinstance(s, dict) and s.get("key")
        ]
    )


@_guarded
def plex_subtitle_attach(server: int, rating_key: str, key: str, codec: str, language: str) -> str:
    """Have the Plex server download a search result and attach it to the
    item (as Plex's apps do; it stays there for every client). Returns the
    item's sidecar subtitles afterwards -- the new one among them -- in the
    same shape as plex_resolve's `sidecar_subtitles`."""
    creds = _plex_server(server)
    before = {s["url"] for s in _plex_extras(creds, rating_key).get("sidecar_subtitles", [])}
    r = requests.put(
        f"{creds.base_url}/library/metadata/{rating_key}/subtitles",
        params={"key": key, "codec": codec, "language": language, "hearingImpaired": "0", "forced": "0"},
        headers={"Accept": "application/json", "X-Plex-Token": creds.token},
        timeout=60,
    )
    r.raise_for_status()
    # Plex downloads it in the background after answering: a few seconds
    # on a real server. Wait for the new file to show up on the item.
    sidecars: list[dict[str, Any]] = []
    for _ in range(_SUBTITLE_WAIT_TRIES):
        sidecars = _plex_extras(creds, rating_key).get("sidecar_subtitles", [])
        if any(s["url"] not in before for s in sidecars):
            break
        time.sleep(_SUBTITLE_WAIT_S)
    return _ok(sidecars)


_SUBTITLE_WAIT_TRIES = 15
_SUBTITLE_WAIT_S = 1.0


_SUBTITLE_MIME = {"srt": "application/x-subrip", "ass": "text/x-ssa", "ssa": "text/x-ssa", "vtt": "text/vtt"}


def _plex_extras(creds: tv_plex.PlexCreds, rating_key: str) -> dict[str, Any]:
    """What the details page shows beyond tvdinner's VodItem: runtime,
    certificate, genres, cast, studio, air date, episode numbering, the
    file's quality -- and Plex's sidecar subtitle files, which aren't in
    the media file so the player must be told about them. One GET of the
    item's metadata (the same endpoint tvdinner's resolve reads). Empty
    on any failure: the details page just shows less."""
    try:
        r = requests.get(
            f"{creds.base_url}/library/metadata/{rating_key}",
            headers={"Accept": "application/json", "X-Plex-Token": creds.token},
            timeout=10,
        )
        r.raise_for_status()
        meta = ((r.json().get("MediaContainer") or {}).get("Metadata") or [{}])[0]
    except Exception as exc:  # noqa: BLE001 -- cosmetic
        sys.stderr.write(redact(f"plex details for {rating_key} unavailable: {exc}\n"))
        return {}

    def tags(key: str, limit: int = 8) -> list[str]:
        return [str(t["tag"]) for t in (meta.get(key) or []) if isinstance(t, dict) and t.get("tag")][:limit]

    media = (meta.get("Media") or [{}])[0] if isinstance(meta.get("Media"), list) else {}
    parts = media.get("Part") or [] if isinstance(media, dict) else []
    streams = [s for p in parts if isinstance(p, dict) for s in (p.get("Stream") or []) if isinstance(s, dict)]
    sidecars = []
    for st in streams:
        if st.get("streamType") == 3 and st.get("key") and str(st.get("codec", "")).lower() in _SUBTITLE_MIME:
            sidecars.append(
                {
                    "url": f"{creds.base_url}{st['key']}?X-Plex-Token={creds.token}",
                    "mime": _SUBTITLE_MIME[str(st["codec"]).lower()],
                    "language": st.get("languageTag") or st.get("languageCode"),
                    "label": st.get("displayTitle") or st.get("language"),
                }
            )
    audio = next((st for st in streams if st.get("streamType") == 2 and st.get("selected")), None) or next(
        (st for st in streams if st.get("streamType") == 2), None
    )
    return {
        "duration_ms": meta.get("duration") or media.get("duration"),
        "content_rating": meta.get("contentRating"),
        "originally_available": meta.get("originallyAvailableAt"),
        "studio": meta.get("studio"),
        "tagline": meta.get("tagline"),
        "genres": tags("Genre", 4),
        "cast": tags("Role", 6),
        "writers": tags("Writer", 3),
        "season": meta.get("parentIndex"),
        "episode": meta.get("index"),
        "video": _video_label(media),
        "audio": audio.get("displayTitle") if audio else None,
        "subtitles": [st.get("displayTitle") or st.get("language") for st in streams if st.get("streamType") == 3],
        "sidecar_subtitles": sidecars,
    }


# --------------------------------------------------------------------------
# Linking a Plex account (plex.tv/link)
#
# Plex's own PIN flow, as its TV apps use: the TV shows a short code, the
# user enters it at plex.tv/link, and the account's token then lists every
# server it can reach -- each with its own access token and connections.
# Not in tvdinner (which takes plex:// links); plain requests to plex.tv.

_PLEX_TV = "https://plex.tv/api/v2"


def _plex_tv_headers(token: str | None = None) -> dict[str, str]:
    headers = {
        "Accept": "application/json",
        "X-Plex-Product": "radiotimes",
        "X-Plex-Client-Identifier": _plex_client_id or "radiotimes",
    }
    if token:
        headers["X-Plex-Token"] = token
    return headers


@_guarded
def plex_link_start(files_dir: str) -> str:
    """Ask plex.tv for a PIN. Returns `{"id", "code"}`; show the code."""
    global _plex_client_id
    if _plex_client_id is None:
        _plex_client_id = tv_plex.load_plex_client_id(Path(files_dir) / "plex_client_id.json")
    r = requests.post(f"{_PLEX_TV}/pins", headers=_plex_tv_headers(), timeout=15)
    r.raise_for_status()
    pin = r.json()
    return _ok({"id": pin["id"], "code": pin["code"]})


@_guarded
def plex_link_poll(pin_id: int) -> str:
    """`{"done": false}` until the code has been entered at plex.tv/link,
    then `{"done": true, "servers": [{"name", "link"}]}` -- a plex:// link
    per server, ready for plex_configure. Expired PINs are an error."""
    r = requests.get(f"{_PLEX_TV}/pins/{int(pin_id)}", headers=_plex_tv_headers(), timeout=15)
    if r.status_code == 404:
        return _err("The code expired. Start again.")
    r.raise_for_status()
    token = r.json().get("authToken")
    if not token:
        return _ok({"done": False})
    r = requests.get(f"{_PLEX_TV}/resources", params={"includeHttps": "1"}, headers=_plex_tv_headers(token), timeout=15)
    r.raise_for_status()
    servers = []
    for res in r.json():
        if "server" not in str(res.get("provides", "")).split(",") or not res.get("accessToken"):
            continue
        link = _plex_link_for(res)
        if link:
            servers.append({"name": res.get("name") or "Plex", "link": link})
    return _ok({"done": True, "servers": servers})


def _plex_link_for(resource: dict) -> str | None:
    """A server's best connection as a tvdinner plex:// link: a direct
    connection on the home network (plain http to its address -- what a
    TV next to the server should use), else a direct remote one (https via
    its plex.direct name), never a relay."""
    conns = [c for c in resource.get("connections") or [] if isinstance(c, dict) and not c.get("relay")]
    token = resource["accessToken"]
    local = next((c for c in conns if c.get("local") and c.get("address") and c.get("port")), None)
    if local:
        return f"plex://{local['address']}:{local['port']}?X-Plex-Token={token}"
    remote = next((c for c in conns if c.get("uri", "").startswith("https://")), None)
    if remote:
        host = remote["uri"].split("://", 1)[1].rstrip("/")
        return f"plexs://{host}?X-Plex-Token={token}"
    return None
