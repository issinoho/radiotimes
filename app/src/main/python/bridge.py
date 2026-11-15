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
import platform
import shutil
import sys
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from tvdinner import __version__ as TVDINNER_VERSION
from tvdinner import epg as tv_epg
from tvdinner.m3u import Channel, Playlist, load_playlist
from tvdinner.tvtimes import (
    TvtimesFeed,
    fetch_tvtimes_favourites,
    fetch_tvtimes_watchlist,
    parse_tvtimes_url,
    tvtimes_epg_url,
    tvtimes_playlist_url,
)

_feed: TvtimesFeed | None = None
_cache_dir: Path | None = None
_playlist: Playlist | None = None
_epg: tv_epg.Epg | None = None


# --------------------------------------------------------------------------
# helpers


def _ok(payload: Any) -> str:
    return json.dumps(payload)


def _err(message: str) -> str:
    return json.dumps({"error": message})


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
            traceback.print_exc(file=sys.stderr)
            return _err(f"{type(exc).__name__}: {exc}")

    wrapper.__name__ = fn.__name__
    wrapper.__doc__ = fn.__doc__
    return wrapper


def _ms(moment: datetime) -> int:
    return int(moment.timestamp() * 1000)


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


@_guarded
def load_guide() -> str:
    """Fetch and parse the XMLTV guide for the loaded line-up.

    Uses tvtimes' EPG URL derived from the link the user typed, not the
    playlist's `url-tvg=` header, for the reason tvdinner's tvtimes module
    gives: that header carries tvtimes' configured public origin, which need
    not be reachable from the TV.
    """
    global _epg
    feed = _require_feed()
    if _playlist is None:
        return _err("Load channels before the guide")
    epg = tv_epg.load_epg_for_playlist(_playlist, override=tvtimes_epg_url(feed), cache_dir=_epg_cache_dir())
    if epg is None:
        return _err(f"Could not load the guide from {feed.base_url}")
    _epg = epg
    return _ok(
        {
            "epg_channels": len(epg.channels),
            "programmes": sum(len(p) for p in epg.programmes.values()),
            "pre_corrected": epg.times_already_corrected,
        }
    )


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
# Phase 0 probe


def _max_rss_kb() -> int | None:
    try:
        import resource

        # Kilobytes on Linux, which is what Android is.
        return int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    except Exception:  # noqa: BLE001 -- informational only
        return None


@_guarded
def probe(tvtimes_url: str, cache_dir: str) -> str:
    """Measure what matters before building a UI on this engine.

    The question Phase 0 exists to answer is whether tvdinner's pure-Python
    XMLTV parse is tolerable on a Fire TV Stick. So this times a cold load
    (cache wiped, network fetch and full parse), then a warm one (tvdinner's
    parsed-EPG pickle cache), and records peak memory and feed size. A
    handful of now/next samples confirm the guide actually lines up with
    the channels rather than parsing to nothing.
    """
    report: dict[str, Any] = {
        "python": sys.version.split()[0],
        "machine": platform.machine(),
        "tvdinner": TVDINNER_VERSION,
        "rss_start_kb": _max_rss_kb(),
    }
    steps: list[dict[str, Any]] = []
    report["steps"] = steps

    def step(name: str, fn: Callable[[], str]) -> dict[str, Any] | list[Any] | None:
        started = time.monotonic()
        result = json.loads(fn())
        entry: dict[str, Any] = {"step": name, "seconds": round(time.monotonic() - started, 2), "rss_kb": _max_rss_kb()}
        if isinstance(result, dict) and "error" in result:
            entry["error"] = result["error"]
            steps.append(entry)
            return None
        steps.append(entry)
        return result

    if step("configure", lambda: configure(tvtimes_url, cache_dir)) is None:
        return _ok(report)

    epg_dir = _epg_cache_dir()
    if epg_dir is not None:
        shutil.rmtree(epg_dir, ignore_errors=True)

    channels = step("channels", load_channels)
    if channels is None:
        return _ok(report)
    report["channels"] = len(channels)
    report["first_channel"] = channels[0] if channels else None

    cold = step("guide_cold", load_guide)
    if cold is None:
        return _ok(report)
    report["guide"] = cold

    if epg_dir is not None:
        raw = tv_epg.cache_path_for(epg_dir, tvtimes_epg_url(_require_feed()))
        report["epg_bytes"] = raw.stat().st_size if raw.is_file() else None

    global _epg
    _epg = None
    step("guide_warm", load_guide)

    started = time.monotonic()
    nn = json.loads(now_next())
    report["now_next_seconds"] = round(time.monotonic() - started, 3)
    report["channels_with_guide"] = len(nn)

    def now_title(url: str) -> str | None:
        entry = nn.get(url)
        return entry["now"]["title"] if entry and entry["now"] else None

    report["samples"] = [{"channel": c["name"], "now": now_title(c["url"])} for c in channels[:5]]
    report["rss_end_kb"] = _max_rss_kb()
    return _ok(report)
