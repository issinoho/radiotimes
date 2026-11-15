"""A fake tvtimes export feed, for trying radiotimes in the Android TV
emulator without a real tvtimes or IPTV account.

60 channels in 5 groups, a 24 h guide starting two hours ago, and
/stream/<id> redirecting (302, as tvtimes does) to a public HLS test
stream. Listens on 127.0.0.1 only; the emulator reaches the host's
loopback as 10.0.2.2.

    python3 tools/fake_tvtimes.py
    adb -s emulator-5554 shell am start -n com.issinoho.radiotimes/.MainActivity \
      --es url "'tvtimes://10.0.2.2:8899?token=testtoken'"

To test reconnecting, `--ts-channel 5` sends channel 5 to a live MPEG-TS
stream you run (and kill, and restart) yourself:

    ffmpeg -re -f lavfi -i testsrc=size=640x360:rate=25 -f lavfi -i sine \
      -c:v libx264 -preset ultrafast -tune zerolatency -c:a aac \
      -f mpegts -listen 1 http://127.0.0.1:8900/live.ts
"""
import datetime as dt
import json
import http.server
import random
import sys

HOST = "http://10.0.2.2:8899"
TOKEN = "testtoken"
HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
TS_CHANNEL = int(sys.argv[sys.argv.index("--ts-channel") + 1]) if "--ts-channel" in sys.argv else None
TS = "http://10.0.2.2:8900/live.ts"
GROUPS = ["UK", "News", "Movies", "Sport", "Kids"]
CH = [(i, f"{GROUPS[i % 5]} {i:02d}", GROUPS[i % 5]) for i in range(1, 61)]
# Real logos for the first few channels, a mix of kinds: TCM is black on
# transparent (needs inverting on a dark tile), the others are coloured.
LOGOS = [
    "https://static.epg.best/us/TurnerClassicMovies.us.png",
    "https://static.epg.best/us/CNN.us.png",
    "https://static.epg.best/uk/Channel4.uk.png",
    "https://static.epg.best/us/HBO.us.png",
]
TITLES = ["The Evening News", "Quiz Night", "A Very Long Documentary About the History of Lighthouses", "Film: The Big Heist", "Football Live", "Cartoons", "Weather"]

def playlist():
    out = [f'#EXTM3U url-tvg="{HOST}/api/exports/epg.xml?token={TOKEN}"']
    for i, name, g in CH:
        logo = f' tvg-logo="{LOGOS[i - 1]}"' if i <= len(LOGOS) else ""
        out.append(f'#EXTINF:-1 tvg-id="{i}" tvg-name="{name}"{logo} group-title="{g}",{name}')
        out.append(f"{HOST}/api/exports/stream/{i}?token={TOKEN}")
    return "\n".join(out) + "\n"

def epg():
    now = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0) - dt.timedelta(hours=2)
    f = lambda t: t.strftime("%Y%m%d%H%M%S +0000")
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="tvtimes">']
    for i, name, _ in CH:
        out.append(f'<channel id="{i}"><display-name>{name}</display-name></channel>')
    for i, name, _ in CH:
        r = random.Random(i); t = now
        while t < now + dt.timedelta(hours=24):
            d = dt.timedelta(minutes=r.choice([30, 60, 90, 120]))
            out.append(f'<programme start="{f(t)}" stop="{f(t+d)}" channel="{i}"><title>{r.choice(TITLES)}</title>'
                       f'<desc>An episode on channel {name}. Lorem ipsum dolor sit amet, consectetur adipiscing elit.</desc><category>Entertainment</category></programme>')
            t += d
    out.append("</tv>")
    return "\n".join(out)

def favourites():
    return json.dumps([{"channel_name": n} for n in ("News 01", "Sport 03", "Kids 04")])


def watchlist():
    soon = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0) + dt.timedelta(minutes=30)
    rows = []
    for i, offset in ((2, 0), (6, 120)):
        start = soon + dt.timedelta(minutes=offset)
        rows.append({
            "channel_url": f"{HOST}/api/exports/stream/{i}?token={TOKEN}",
            "channel_name": CH[i - 1][1],
            "title": "Watchlisted Film" if i == 2 else "Watchlisted Match",
            "start": start.isoformat(),
            "stop": (start + dt.timedelta(minutes=90)).isoformat(),
        })
    return json.dumps(rows)


class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        # Watch reporting: print what arrives, store nothing (tvtimes dedupes).
        if f"token={TOKEN}" not in self.path or self.path.split("?")[0] != "/api/exports/watch-events":
            return self.send_error(403)
        events = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0)))).get("events", [])
        for e in events:
            print(f"watch-event | channel {e['channel_id']} {e['started_at']} → {e['ended_at']} "
                  f"{e['title']!r} device={e['device']!r}", flush=True)
        body = json.dumps({"stored": len(events)}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if f"token={TOKEN}" not in self.path:
            return self.send_error(403)
        if path == "/api/exports/playlist.m3u":
            body, ct = playlist().encode(), "audio/x-mpegurl"
        elif path == "/api/exports/epg.xml":
            body, ct = epg().encode(), "application/xml"
        elif path.startswith("/api/exports/stream/"):
            target = TS if TS_CHANNEL is not None and path.endswith(f"/{TS_CHANNEL}") else HLS
            self.send_response(302); self.send_header("Location", target); self.end_headers(); return
        elif path == "/api/exports/favourites.json":
            body, ct = favourites().encode(), "application/json"
        elif path == "/api/exports/watchlist.json":
            body, ct = watchlist().encode(), "application/json"
        else:
            return self.send_error(404)
        self.send_response(200); self.send_header("Content-Type", ct); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

http.server.ThreadingHTTPServer(("127.0.0.1", 8899), H).serve_forever()
