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

import bridge
import pytest
import requests

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

    def fake_post(url: str, *args: object, json: object = None, **kwargs: object) -> _FakeResponse:
        seen.append(url)
        assert url.startswith(BASE), f"request went somewhere other than the typed host: {url}"
        if url[len(BASE) :].split("?", 1)[0] != "/api/exports/watch-events":
            return _FakeResponse(b"", status=404)
        POSTED.append(json)
        return _FakeResponse({"stored": len(json["events"])})

    POSTED.clear()
    monkeypatch.setattr(requests, "get", fake_get)
    monkeypatch.setattr(requests, "post", fake_post)
    return seen


POSTED: list = []


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


# --------------------------------------------------------------------------
# Plex, against a fake server shaped like the real responses tvdinner parses

PLEX = "http://plex.example.test:32400"
PLEX_TOKEN = "pl3xs3cret"
PLEX_LINK = f"plex://plex.example.test:32400?X-Plex-Token={PLEX_TOKEN}"

PLEX_ROUTES: dict[str, object] = {
    "/": {"MediaContainer": {"friendlyName": "TestPlex"}},
    "/library/sections": {
        "MediaContainer": {
            "Directory": [
                {"key": "1", "title": "Films", "type": "movie"},
                {"key": "2", "title": "TV", "type": "show"},
                {"key": "3", "title": "Music", "type": "artist"},
            ]
        }
    },
    "/library/sections/2/all": {"MediaContainer": {"Metadata": []}},
    "/library/sections/1/all": {
        "MediaContainer": {
            "Metadata": [
                {"ratingKey": "10", "title": "Casino Royale", "type": "movie", "year": 2006, "duration": 8700000,
                 "thumb": "/library/metadata/10/thumb/1", "viewOffset": 600000},
            ]
        }
    },
    "/library/metadata/10": {
        "MediaContainer": {
            "Metadata": [
                {"ratingKey": "10", "title": "Casino Royale", "type": "movie", "year": 2006, "summary": "Bond.",
                 "viewOffset": 600000, "duration": 8700000, "contentRating": "12",
                 "Genre": [{"tag": "Action"}], "Role": [{"tag": "Daniel Craig"}, {"tag": "Eva Green"}],
                 "Media": [{"videoResolution": "1080", "videoCodec": "h264", "Part": [{"key": "/library/parts/99/file.mkv",
                   "Stream": [
                       {"streamType": 2, "displayTitle": "English (AC3 5.1)", "selected": True},
                       {"streamType": 3, "codec": "vobsub", "displayTitle": "English (embedded)"},
                       {"streamType": 3, "codec": "srt", "key": "/library/streams/7", "languageTag": "en",
                        "displayTitle": "English (SRT External)"},
                   ]}]}]},
            ]
        }
    },
}


@pytest.fixture
def plex(monkeypatch: pytest.MonkeyPatch, tmp_path) -> list[str]:
    seen: list[str] = []

    def fake_get(url: str, *args: object, **kwargs: object) -> _FakeResponse:
        seen.append(url)
        assert url.startswith(PLEX)
        path = url[len(PLEX):].split("?", 1)[0] or "/"
        if path not in PLEX_ROUTES:
            return _FakeResponse(b"", status=404)
        return _FakeResponse(PLEX_ROUTES[path])

    monkeypatch.setattr(requests, "get", fake_get)
    servers = json.loads(bridge.plex_configure(json.dumps([PLEX_LINK, "http://not-plex"]), str(tmp_path)))
    assert servers[0] == {"base_url": PLEX, "name": "TestPlex"}
    assert "error" in servers[1]  # keeps its slot so indexes line up
    return seen


def test_plex_root_lists_on_deck_and_video_libraries_only(plex) -> None:
    nodes = json.loads(bridge.plex_children(0))
    assert [(n["title"], n["kind"]) for n in nodes] == [
        ("On Deck", "continue_watching"), ("Films", "library_movie"), ("TV", "library_show"),
    ]
    assert all(n["container"] for n in nodes)


def test_plex_library_children_and_resolve(plex) -> None:
    films = json.loads(bridge.plex_children(0))[1]
    movies = json.loads(bridge.plex_children(0, json.dumps(films)))
    assert movies[0]["title"] == "Casino Royale" and movies[0]["container"] is False
    assert PLEX_TOKEN in movies[0]["thumb"]  # the image loader needs it
    # Grid posters come resized by Plex's photo transcoder, not full size.
    assert movies[0]["thumb"].startswith(f"{PLEX}/photo/:/transcode?width=240&height=360")
    assert "url=%2Flibrary%2Fmetadata%2F10%2Fthumb%2F1" in movies[0]["thumb"]
    item = json.loads(bridge.plex_resolve(0, json.dumps(movies[0])))
    assert item["url"] == f"{PLEX}/library/parts/99/file.mkv?X-Plex-Token={PLEX_TOKEN}"
    assert item["resume_seconds"] == 600
    assert item["description"] == "Bond."
    # Details beyond tvdinner's VodItem
    assert item["duration_ms"] == 8700000 and item["content_rating"] == "12"
    assert item["genres"] == ["Action"] and item["cast"] == ["Daniel Craig", "Eva Green"]
    assert item["video"] == "1080p H264" and item["audio"] == "English (AC3 5.1)"
    assert item["sidecar_subtitles"] == [{
        "url": f"{PLEX}/library/streams/7?X-Plex-Token={PLEX_TOKEN}", "mime": "application/x-subrip",
        "language": "en", "label": "English (SRT External)",
    }]


def test_plex_resolve_survives_missing_details(plex, monkeypatch) -> None:
    films = json.loads(bridge.plex_children(0))[1]
    movie = json.loads(bridge.plex_children(0, json.dumps(films)))[0]
    real_get = requests.get
    calls = {"n": 0}

    def flaky(url: str, *args: object, **kwargs: object):
        # tvdinner's resolve GET succeeds; the details GET (second) fails.
        if "/library/metadata/10" in url:
            calls["n"] += 1
            if calls["n"] == 2:
                raise requests.ConnectionError("gone")
        return real_get(url, *args, **kwargs)

    monkeypatch.setattr(requests, "get", flaky)
    item = json.loads(bridge.plex_resolve(0, json.dumps(movie)))
    assert item["url"].endswith("file.mkv?X-Plex-Token=" + PLEX_TOKEN)
    assert "genres" not in item


def test_plex_errors_never_carry_the_token(plex, monkeypatch, capfd) -> None:
    def down(url: str, *args: object, **kwargs: object) -> None:
        raise requests.ConnectionError(f"refused for {url}?X-Plex-Token={PLEX_TOKEN}")

    monkeypatch.setattr(requests, "get", down)
    out = bridge.plex_children(0)
    assert "error" in json.loads(out)
    assert PLEX_TOKEN not in out
    assert PLEX_TOKEN not in capfd.readouterr().err


def test_plex_unknown_server_is_an_error(plex) -> None:
    assert "error" in json.loads(bridge.plex_children(5))


def test_plex_name_falls_back_to_host(monkeypatch, tmp_path) -> None:
    def down(*args: object, **kwargs: object) -> None:
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(requests, "get", down)
    servers = json.loads(bridge.plex_configure(json.dumps([PLEX_LINK]), str(tmp_path)))
    assert servers == [{"base_url": PLEX, "name": "plex.example.test:32400"}]



# --------------------------------------------------------------------------
# plex.tv/link


class _PlexTv:
    def __init__(self) -> None:
        self.linked = False

    def post(self, url: str, *args: object, **kwargs: object) -> _FakeResponse:
        assert url == "https://plex.tv/api/v2/pins"
        assert kwargs["headers"]["X-Plex-Product"] == "radiotimes"
        return _FakeResponse({"id": 42, "code": "AB12", "authToken": None})

    def get(self, url: str, *args: object, **kwargs: object) -> _FakeResponse:
        if url == "https://plex.tv/api/v2/pins/42":
            return _FakeResponse({"id": 42, "authToken": "acct-token" if self.linked else None})
        if url == "https://plex.tv/api/v2/pins/43":
            return _FakeResponse(b"", status=404)
        if url == "https://plex.tv/api/v2/resources":
            assert kwargs["headers"]["X-Plex-Token"] == "acct-token"
            return _FakeResponse([
                {"name": "SmithPlex", "provides": "server", "accessToken": "srv1", "connections": [
                    {"protocol": "https", "address": "203.0.113.5", "port": 32400, "uri": "https://203-0-113-5.abc.plex.direct:32400", "local": False},
                    {"protocol": "https", "address": "192.168.0.192", "port": 32400, "uri": "https://192-168-0-192.abc.plex.direct:32400", "local": True},
                ]},
                {"name": "Away", "provides": "server", "accessToken": "srv2", "connections": [
                    {"protocol": "https", "address": "198.51.100.7", "port": 443, "uri": "https://198-51-100-7.def.plex.direct:443", "local": False},
                    {"protocol": "https", "address": "relay", "port": 8443, "uri": "https://relay.plex.direct:8443", "local": False, "relay": True},
                ]},
                {"name": "My phone", "provides": "client,player", "accessToken": "x", "connections": []},
            ])
        raise AssertionError(url)


def test_plex_link_flow(monkeypatch, tmp_path) -> None:
    fake = _PlexTv()
    monkeypatch.setattr(requests, "post", fake.post)
    monkeypatch.setattr(requests, "get", fake.get)
    assert json.loads(bridge.plex_link_start(str(tmp_path))) == {"id": 42, "code": "AB12"}
    assert json.loads(bridge.plex_link_poll(42)) == {"done": False}
    fake.linked = True
    result = json.loads(bridge.plex_link_poll(42))
    assert result == {"done": True, "servers": [
        {"name": "SmithPlex", "link": "plex://192.168.0.192:32400?X-Plex-Token=srv1"},
        {"name": "Away", "link": "plexs://198-51-100-7.def.plex.direct:443?X-Plex-Token=srv2"},
    ]}
    # The links are what plex_configure takes.
    assert bridge.tv_plex.parse_plex_url(result["servers"][1]["link"]).base_url == "https://198-51-100-7.def.plex.direct:443"


def test_plex_link_expired(monkeypatch) -> None:
    monkeypatch.setattr(requests, "get", _PlexTv().get)
    assert "expired" in json.loads(bridge.plex_link_poll(43))["error"]



def test_plex_library_recent_views(plex, monkeypatch) -> None:
    films = json.loads(bridge.plex_children(0))[1]
    seen: list[dict] = []
    real_get = requests.get

    def spy(url: str, *args: object, **kwargs: object):
        seen.append(dict(kwargs.get("params") or {}))
        return real_get(url, *args, **kwargs)

    monkeypatch.setattr(requests, "get", spy)
    nodes = json.loads(bridge.plex_library_view(0, json.dumps(films), "recently_added"))
    assert [n["title"] for n in nodes] == ["Casino Royale"]
    assert seen[-1]["sort"] == "addedAt:desc" and seen[-1]["type"] == "1"
    json.loads(bridge.plex_library_view(0, json.dumps(films), "recently_released"))
    assert seen[-1]["sort"] == "originallyAvailableAt:desc"
    tv = json.loads(bridge.plex_children(0))[2]
    bridge.plex_library_view(0, json.dumps(tv), "recently_added")
    assert seen[-1]["type"] == "4"  # episodes, for a TV library
    assert [n["title"] for n in json.loads(bridge.plex_library_view(0, json.dumps(films), "all"))] == ["Casino Royale"]
    assert "error" in json.loads(bridge.plex_library_view(0, json.dumps(films), "sideways"))



def test_plex_subtitle_search_and_attach(plex, monkeypatch) -> None:
    results = {"MediaContainer": {"Stream": [
        {"key": "/library/streams/900", "codec": "srt", "languageCode": "eng", "title": "Casino.Royale.2006.en",
         "providerTitle": "OpenSubtitles"},
        {"displayTitle": "no key, skipped"},
    ]}}
    real_get = requests.get
    puts: list[tuple[str, dict]] = []

    def get(url: str, *args: object, **kwargs: object):
        if url.endswith("/library/metadata/10/subtitles"):
            assert kwargs["params"]["language"] == "en"
            return _FakeResponse(results)
        return real_get(url, *args, **kwargs)

    def put(url: str, *args: object, **kwargs: object):
        puts.append((url, dict(kwargs.get("params") or {})))
        return _FakeResponse({})

    # The new file appears on the item only on the second look after the
    # PUT, as on a real server (Plex downloads it in the background).
    looks_after_put = {"n": 0}
    real_route = PLEX_ROUTES["/library/metadata/10"]

    def metadata_with_new_sub() -> dict:
        item = json.loads(json.dumps(real_route))
        streams = item["MediaContainer"]["Metadata"][0]["Media"][0]["Part"][0]["Stream"]
        streams.append({"streamType": 3, "codec": "srt", "key": "/library/streams/901", "languageTag": "en",
                        "displayTitle": "English (SRT OpenSubtitles)"})
        return item

    def get_with_delay(url: str, *args: object, **kwargs: object):
        if url.endswith("/library/metadata/10") and puts:
            looks_after_put["n"] += 1
            if looks_after_put["n"] >= 2:
                return _FakeResponse(metadata_with_new_sub())
        return get(url, *args, **kwargs)

    monkeypatch.setattr(bridge, "_SUBTITLE_WAIT_S", 0)
    monkeypatch.setattr(requests, "get", get_with_delay)
    monkeypatch.setattr(requests, "put", put)
    found = json.loads(bridge.plex_subtitle_search(0, "10", "en"))
    assert found == [{"key": "/library/streams/900", "title": "Casino.Royale.2006.en", "provider": "OpenSubtitles",
                      "language": "eng", "codec": "srt", "hearing_impaired": False}]
    sidecars = json.loads(bridge.plex_subtitle_attach(0, "10", "/library/streams/900", "srt", "eng"))
    assert puts == [(f"{PLEX}/library/metadata/10/subtitles",
                     {"key": "/library/streams/900", "codec": "srt", "language": "eng", "hearingImpaired": "0", "forced": "0"})]
    # Waited for the new file; the item's sidecars now include it.
    assert looks_after_put["n"] >= 2
    assert [s["url"] for s in sidecars] == [
        f"{PLEX}/library/streams/7?X-Plex-Token={PLEX_TOKEN}",
        f"{PLEX}/library/streams/901?X-Plex-Token={PLEX_TOKEN}",
    ]


def test_plex_subtitle_search_failure_is_an_error(plex, monkeypatch) -> None:
    def down(*args: object, **kwargs: object) -> None:
        raise requests.ConnectionError(f"refused X-Plex-Token={PLEX_TOKEN}")

    monkeypatch.setattr(requests, "get", down)
    out = bridge.plex_subtitle_search(0, "10", "en")
    assert "error" in json.loads(out) and PLEX_TOKEN not in out



def test_plex_transcode_url_and_stop(plex, monkeypatch) -> None:
    import urllib.parse as up

    decisions: list[str] = []
    real_get = requests.get

    def get_decision(u: str, *args: object, **kwargs: object):
        if "/video/:/transcode/universal/decision?" in u:
            decisions.append(u)
            return _FakeResponse({"MediaContainer": {"generalDecisionCode": 1001}})
        return real_get(u, *args, **kwargs)

    monkeypatch.setattr(requests, "get", get_decision)
    url = json.loads(bridge.plex_transcode_url(0, "10", "sess-1"))["url"]
    # The decision request comes first, with the same parameters.
    assert len(decisions) == 1 and decisions[0].split("?", 1)[1] == url.split("?", 1)[1]
    parts = up.urlsplit(url)
    assert f"{parts.scheme}://{parts.netloc}{parts.path}" == f"{PLEX}/video/:/transcode/universal/start.m3u8"
    q = dict(up.parse_qsl(parts.query))
    assert q["path"] == "/library/metadata/10" and q["protocol"] == "hls" and q["session"] == "sess-1"
    assert q["X-Plex-Platform"] == "Chrome" and q["X-Plex-Token"] == PLEX_TOKEN

    seen: list[tuple[str, dict]] = []

    def get(u: str, *args: object, **kwargs: object):
        seen.append((u, dict(kwargs.get("params") or {})))
        return _FakeResponse({})

    monkeypatch.setattr(requests, "get", get)
    assert json.loads(bridge.plex_transcode_stop(0, "sess-1")) == {"ok": True}
    assert seen == [(f"{PLEX}/video/:/transcode/universal/stop", {"session": "sess-1"})]


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def test_watch_recorded_and_reported_with_channel_id(engine, tmp_path) -> None:
    history = str(tmp_path / "history.jsonl")
    url = f"{BASE}/api/exports/stream/1?token={TOKEN}"
    end = _now_ms()
    assert json.loads(bridge.record_watch(history, url, "Quiz", "BBC One", end - 600_000, end)) == {"recorded": True}
    result = json.loads(bridge.report_watch(history, "living room"))
    assert result == {"sent": 1, "stored": 1}
    (event,) = POSTED[0]["events"]
    assert event["channel_id"] == "1"
    assert event["title"] == "Quiz"
    assert event["device"] == "living room"
    assert datetime.fromisoformat(event["ended_at"]) == datetime.fromtimestamp(end / 1000, tz=timezone.utc)
    # The token travels only in the request URL, never the body.
    assert TOKEN not in json.dumps(POSTED[0])


def test_flicking_past_a_channel_is_not_recorded(engine, tmp_path) -> None:
    history = str(tmp_path / "history.jsonl")
    end = _now_ms()
    url = f"{BASE}/api/exports/stream/1?token={TOKEN}"
    assert json.loads(bridge.record_watch(history, url, "", "BBC One", end - 2_000, end)) == {"recorded": False}
    assert json.loads(bridge.report_watch(history, "")) == {"sent": 0, "stored": 0}
    assert POSTED == []


def test_report_skips_other_sources_and_old_watches(engine, tmp_path) -> None:
    history = str(tmp_path / "history.jsonl")
    end = _now_ms()
    bridge.record_watch(history, "http://elsewhere/live.m3u8", "Other", "X", end - 600_000, end)
    week_ago = end - 8 * 86_400_000
    bridge.record_watch(history, f"{BASE}/api/exports/stream/1?token={TOKEN}", "Old", "BBC One", week_ago - 600_000, week_ago)
    assert json.loads(bridge.report_watch(history, "")) == {"sent": 0, "stored": 0}


def test_report_failure_is_a_redacted_error(engine, tmp_path, monkeypatch) -> None:
    history = str(tmp_path / "history.jsonl")
    end = _now_ms()
    bridge.record_watch(history, f"{BASE}/api/exports/stream/1?token={TOKEN}", "Quiz", "BBC One", end - 600_000, end)

    def down(url: str, *args: object, **kwargs: object) -> None:
        raise requests.ConnectionError(f"refused {url}")

    monkeypatch.setattr(requests, "post", down)
    error = json.loads(bridge.report_watch(history, ""))["error"]
    assert TOKEN not in error
