"""A fake tvtimes export feed, for trying radiotimes in the Android TV
emulator without a real tvtimes or IPTV account.

60 channels in 5 groups, a 24 h guide starting two hours ago, and
/stream/<id> redirecting (302, as tvtimes does) to a public HLS test
stream. Listens on 127.0.0.1 only; the emulator reaches the host's
loopback as 10.0.2.2.

    python3 tools/fake_tvtimes.py
    adb -s emulator-5554 shell am start -n com.issinoho.radiotimes/.MainActivity \
      --es url "'tvtimes://10.0.2.2:8899?token=testtoken'"
"""
import datetime as dt
import http.server
import random

HOST = "http://10.0.2.2:8899"
TOKEN = "testtoken"
HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
GROUPS = ["UK", "News", "Movies", "Sport", "Kids"]
CH = [(i, f"{GROUPS[i % 5]} {i:02d}", GROUPS[i % 5]) for i in range(1, 61)]
TITLES = ["The Evening News", "Quiz Night", "A Very Long Documentary About the History of Lighthouses", "Film: The Big Heist", "Football Live", "Cartoons", "Weather"]

def playlist():
    out = [f'#EXTM3U url-tvg="{HOST}/api/exports/epg.xml?token={TOKEN}"']
    for i, name, g in CH:
        out.append(f'#EXTINF:-1 tvg-id="{i}" tvg-name="{name}" group-title="{g}",{name}')
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

class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        if f"token={TOKEN}" not in self.path:
            return self.send_error(403)
        if path == "/api/exports/playlist.m3u":
            body, ct = playlist().encode(), "audio/x-mpegurl"
        elif path == "/api/exports/epg.xml":
            body, ct = epg().encode(), "application/xml"
        elif path.startswith("/api/exports/stream/"):
            self.send_response(302); self.send_header("Location", HLS); self.end_headers(); return
        elif path in ("/api/exports/favourites.json", "/api/exports/watchlist.json"):
            body, ct = b"[]", "application/json"
        else:
            return self.send_error(404)
        self.send_response(200); self.send_header("Content-Type", ct); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

http.server.ThreadingHTTPServer(("127.0.0.1", 8899), H).serve_forever()
