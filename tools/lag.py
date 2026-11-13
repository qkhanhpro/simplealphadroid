"""Measure MJPEG live-view lag: PC arrival time minus the phone's frame-capture time (X-Ts), clock offset via adb.
usage: python lag.py <host:port> <adb-serial> <query> <seconds>"""
import socket, subprocess, sys, time, statistics

host, serial, query, secs = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])


def offset_ms():
    best = None
    for _ in range(5):
        t0 = time.time() * 1000
        out = subprocess.run(["adb", "-s", serial, "shell", "date +%s%3N"], capture_output=True, text=True).stdout.strip()
        t1 = time.time() * 1000
        rtt = t1 - t0
        off = (t0 + t1) / 2 - int(out)   # pc - phone
        if best is None or rtt < best[0]:
            best = (rtt, off)
    return best


rtt, off = offset_ms()
h, p = host.split(":")
s = socket.create_connection((h, int(p)), timeout=10)
s.sendall(f"GET /stream?{query} HTTP/1.0\r\n\r\n".encode())
buf = b""
lags, sizes, t_start = [], [], time.time()


def need(n):
    global buf
    while len(buf) < n:
        d = s.recv(65536)
        if not d:
            raise EOFError
        buf += d


while time.time() - t_start < secs:
    need(4)
    i = buf.find(b"--frame\r\n")
    while i < 0:
        need(len(buf) + 1); i = buf.find(b"--frame\r\n")
    j = buf.find(b"\r\n\r\n", i)
    while j < 0:
        need(len(buf) + 1); j = buf.find(b"\r\n\r\n", i)
    hdr = buf[i:j].decode(errors="replace")
    hd = dict(l.split(": ", 1) for l in hdr.split("\r\n")[1:] if ": " in l)
    n = int(hd["Content-Length"])
    need(j + 4 + n)
    now = time.time() * 1000
    buf = buf[j + 4 + n:]
    ts = int(hd.get("X-Ts", 0))
    if ts:
        lags.append(now - off - ts)
    sizes.append(n)
s.close()
el = time.time() - t_start
lags_s = sorted(lags)
q = lambda f: lags_s[min(len(lags_s) - 1, int(f * len(lags_s)))]
print(f"{query}: clock rtt {rtt:.0f}ms | {len(sizes)} frames in {el:.1f}s = {len(sizes)/el:.1f} fps, "
      f"{statistics.mean(sizes)/1024:.0f} KB/frame | lag ms median {q(.5):.0f} p90 {q(.9):.0f} max {lags_s[-1]:.0f} "
      f"| last 10: {[round(x) for x in lags[-10:]]}")
