"""bridge.py against the pinned tvdinner, with tvtimes' export faked.

The fixtures are shaped like tvtimes' real export (app/services/exports.py
there): numeric tvg-ids matching `<channel id>`, stream URLs under
/api/exports/stream/<id>?token=, and `generator-info-name="tvtimes"`, which
is what tells tvdinner the times are already corrected.

`requests.get` is replaced with a fake that dispatches on URL path, the same
approach tvdinner's own loader tests take, rather than a mocking library.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone

import pytest
import requests

import bridge

BASE = "http://tv.example.test:8888"
TOKEN = "s3cret"
URL = f"tvtimes://tv.example.test:8888?token={TOKEN}"

PLAYLIST = f"""#EXTM3U url-tvg="https://public.example.invalid/api/exports/epg.xml?token={TOKEN}"

#EXTINF:-1 tvg-id="1" tvg-name="BBC One" tvg-logo="http://logo/1.png" group-title="UK",BBC One
{BASE}/api/exports/stream/1?token={TOKEN}
#EXTINF:-1 tvg-id="2" tvg-name="BBC Two" group-title="UK",BBC Two
{BASE}/api/exports/stream/2?token={TOKEN}
#EXTINF:-1 tvg-id="3" tvg-name="No Guide" group-title="Misc",No Guide
{BASE}/api/exports/stream/3?token={TOKEN}
"""

EPG = """<?xml version="1.0" encoding="UTF-8"?>
<tv generator-info-name="tvtimes">
  <channel id="1"><display-name>BBC One</display-name></channel>
  <channel id="2"><display-name>BBC Two</display-name></channel>
  <programme start="20261115180000 +0000" stop="20261115190000 +0000" channel="1"><title>News</title></programme>
  <programme start="20261115190000 +0000" stop="20261115200000 +0000" channel="1"><title>Quiz</title><desc>Questions.</desc></programme>
  <programme start="20261115183000 +0000" stop="20261115213000 +0000" channel="2"><title>Film</title><date>1999</date></programme>
</tv>
"""

FAVOURITES = [{"channel_name": "BBC Two"}, {"channel_name": ""}, "junk"]
WATCHLIST = [
    {
        "channel_url": f"{BASE}/api/exports/stream/1?token={TOKEN}",
        "channel_name": "BBC One",
        "title": "Quiz",
        "start": "2026-11-15T19:00:00+00:00",
        "stop": "2026-11-15T20:00:00+00:00",
    },
    {"title": "malformed, no times"},
]

AT_1830 = int(datetime(2026, 11, 15, 18, 30, tzinfo=timezone.utc).timestamp() * 1000)


class _FakeResponse:
    def __init__(self, body: bytes | object, status: int = 200) -> None:
        self._json = None if isinstance(body, bytes) else body
        self._body = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.status_code = status
        self.headers = {"Content-Length": str(len(self._body))}
        self.encoding = "utf-8"

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code}")

    def iter_content(self, chunk_size: int = 1024):
        for i in range(0, len(self._body), chunk_size):
            yield self._body[i : i + chunk_size]

    def json(self) -> object:
        if self._json is None:
            raise ValueError("not json")
        return self._json


@pytest.fixture
def server(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    """Fake tvtimes. Returns the list of URLs requested, for assertions."""
    seen: list[str] = []
    routes: dict[str, bytes | object] = {
        "/api/exports/playlist.m3u": PLAYLIST.encode(),
        "/api/exports/epg.xml": EPG.encode(),
        "/api/exports/favourites.json": FAVOURITES,
        "/api/exports/watchlist.json": WATCHLIST,
    }

    def fake_get(url: str, *args: object, **kwargs: object) -> _FakeResponse:
        seen.append(url)
        assert url.startswith(BASE), f"request went somewhere other than the typed host: {url}"
        path = url[len(BASE) :].split("?", 1)[0]
        if path not in routes:
            return _FakeResponse(b"", status=404)
        return _FakeResponse(routes[path])

    monkeypatch.setattr(requests, "get", fake_get)
    return seen


@pytest.fixture
def engine(server: list[str], tmp_path):
    assert "error" not in json.loads(bridge.configure(URL, str(tmp_path)))
    return server


def test_configure_rejects_non_tvtimes_link(tmp_path) -> None:
    assert "error" in json.loads(bridge.configure("http://example.com/list.m3u", str(tmp_path)))
    assert "error" in json.loads(bridge.configure("tvtimes://host-without-token", str(tmp_path)))


def test_configure_reports_base_url(tmp_path) -> None:
    out = json.loads(bridge.configure("tvtimess://tv.example.test/sub?token=x", str(tmp_path)))
    assert out == {"base_url": "https://tv.example.test/sub"}


def test_calls_before_configure_return_errors_not_exceptions(monkeypatch) -> None:
    monkeypatch.setattr(bridge, "_feed", None)
    assert "not configured" in json.loads(bridge.load_channels())["error"]


def test_load_channels(engine) -> None:
    channels = json.loads(bridge.load_channels())
    assert [c["name"] for c in channels] == ["BBC One", "BBC Two", "No Guide"]
    assert channels[0]["url"] == f"{BASE}/api/exports/stream/1?token={TOKEN}"
    assert channels[0]["logo"] == "http://logo/1.png"
    assert channels[0]["group"] == "UK"


def test_guide_uses_typed_host_not_url_tvg_header(engine) -> None:
    bridge.load_channels()
    guide = json.loads(bridge.load_guide())
    assert guide["loaded"] is True
    assert (guide["epg_channels"], guide["programmes"], guide["pre_corrected"]) == (2, 3, True)
    # The fake asserts every request hit BASE; this checks the EPG was one.
    assert any("/api/exports/epg.xml" in u for u in engine)


def test_guide_before_channels_is_an_error(engine) -> None:
    assert "error" in json.loads(bridge.load_guide())


def test_now_next_keyed_by_stream_url(engine) -> None:
    bridge.load_channels()
    bridge.load_guide()
    nn = json.loads(bridge.now_next(AT_1830))
    one = nn[f"{BASE}/api/exports/stream/1?token={TOKEN}"]
    assert one["now"]["title"] == "News"
    assert one["next"]["title"] == "Quiz"
    assert one["next"]["description"] == "Questions."
    two = nn[f"{BASE}/api/exports/stream/2?token={TOKEN}"]
    assert two["now"]["title"] == "Film" and two["now"]["year"] == "1999"
    assert two["next"] is None
    assert f"{BASE}/api/exports/stream/3?token={TOKEN}" not in nn


def test_programme_times_are_utc_epoch_ms(engine) -> None:
    bridge.load_channels()
    bridge.load_guide()
    nn = json.loads(bridge.now_next(AT_1830))
    news = nn[f"{BASE}/api/exports/stream/1?token={TOKEN}"]["now"]
    assert news["start"] == int(datetime(2026, 11, 15, 18, 0, tzinfo=timezone.utc).timestamp() * 1000)
    assert news["stop"] - news["start"] == 3_600_000


def test_schedule_window(engine) -> None:
    bridge.load_channels()
    bridge.load_guide()
    url = f"{BASE}/api/exports/stream/1?token={TOKEN}"
    hour = 3_600_000
    titles = [p["title"] for p in json.loads(bridge.schedule(url, AT_1830, AT_1830 + hour))]
    assert titles == ["News", "Quiz"]
    titles = [p["title"] for p in json.loads(bridge.schedule(url, AT_1830 + hour, AT_1830 + 2 * hour))]
    assert titles == ["Quiz"]


def test_favourites_skip_malformed_rows(engine) -> None:
    assert json.loads(bridge.favourites()) == ["BBC Two"]


def test_watchlist_skips_malformed_rows(engine) -> None:
    rows = json.loads(bridge.watchlist())
    assert len(rows) == 1
    assert rows[0]["title"] == "Quiz"
    assert rows[0]["channel_url"] == f"{BASE}/api/exports/stream/1?token={TOKEN}"


def test_unreachable_server_is_an_error(monkeypatch, tmp_path) -> None:
    def down(*args: object, **kwargs: object) -> None:
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(requests, "get", down)
    bridge.configure(URL, str(tmp_path))
    assert "error" in json.loads(bridge.load_channels())
    assert "error" in json.loads(bridge.favourites())


def _epg_fetches(seen: list[str]) -> int:
    return sum("/api/exports/epg.xml" in u for u in seen)


def test_guide_network_load_reports_timing_split(engine) -> None:
    bridge.load_channels()
    guide = json.loads(bridge.load_guide())
    assert guide["source"] == "network"
    assert guide["bytes"] == len(EPG.encode())
    assert guide["download_s"] >= 0 and guide["parse_s"] >= 0
    assert guide["download_s"] + guide["parse_s"] <= guide["total_s"] + 0.01
    assert guide["updated"] > 0
    assert guide["network_failed"] is False


def test_cached_mode_never_touches_the_network(engine) -> None:
    bridge.load_channels()
    assert json.loads(bridge.load_guide("cached")) == {"loaded": False}
    assert _epg_fetches(engine) == 0
    bridge.load_guide()
    guide = json.loads(bridge.load_guide("cached"))
    assert guide["loaded"] is True and guide["source"] == "cache"
    assert "download_s" not in guide
    assert _epg_fetches(engine) == 1


def test_cached_mode_accepts_a_stale_copy(engine, tmp_path) -> None:
    import os

    bridge.load_channels()
    bridge.load_guide()
    week_ago = __import__("time").time() - 7 * 86400
    for f in (tmp_path / "epg").iterdir():
        os.utime(f, (week_ago, week_ago))
    guide = json.loads(bridge.load_guide("cached"))
    assert guide["loaded"] is True and guide["source"] == "cache"
    assert _epg_fetches(engine) == 1


def test_refresh_mode_always_fetches(engine) -> None:
    bridge.load_channels()
    bridge.load_guide()
    guide = json.loads(bridge.load_guide("refresh"))
    assert guide["source"] == "network"
    assert _epg_fetches(engine) == 2


def test_refresh_falls_back_to_stale_copy_when_offline(engine, monkeypatch) -> None:
    bridge.load_channels()
    bridge.load_guide()

    def down(*args: object, **kwargs: object) -> None:
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(requests, "get", down)
    guide = json.loads(bridge.load_guide("refresh"))
    assert guide["loaded"] is True
    assert guide["network_failed"] is True
    assert guide["programmes"] == 3


def test_unknown_guide_mode_is_an_error(engine) -> None:
    bridge.load_channels()
    assert "error" in json.loads(bridge.load_guide("sideways"))


def test_token_never_reaches_stderr_or_errors(monkeypatch, tmp_path, capfd) -> None:
    def down(url: str, *args: object, **kwargs: object) -> None:
        raise requests.ConnectionError(f"refused for {url}")

    monkeypatch.setattr(requests, "get", down)
    bridge.configure(URL, str(tmp_path))
    out = bridge.load_channels() + bridge.favourites()
    captured = capfd.readouterr()
    assert TOKEN not in out
    assert TOKEN not in captured.err
    assert "token=…" in captured.err  # the warning still says which URL


def test_guarded_tracebacks_are_redacted(monkeypatch, tmp_path, capfd) -> None:
    bridge.configure(URL, str(tmp_path))

    def boom(*args: object, **kwargs: object) -> None:
        raise RuntimeError(f"unexpected at {BASE}/api/exports/epg.xml?token={TOKEN}")

    monkeypatch.setattr(bridge, "load_playlist", boom)
    result = json.loads(bridge.load_channels())
    assert TOKEN not in result["error"]
    assert TOKEN not in capfd.readouterr().err
