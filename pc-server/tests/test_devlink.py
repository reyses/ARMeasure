import hashlib
import json
import os
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from armeasure_pc import devlink, net, pairing
from armeasure_pc.access import JsonlLog, Lockout
from armeasure_pc.app import create_app
from conftest import AUTH, TOKEN


def mk(d: Path, name: str, data: bytes, age: float):
    p = d / name
    p.write_bytes(data)
    t = time.time() - age
    os.utime(p, (t, t))
    return p


@pytest.fixture()
def apks(tmp_path):
    d = tmp_path / "apk"
    d.mkdir()
    mk(d, "ARMeasure-debug-aaaaaaa.apk", b"old" * 100, 500)
    mk(d, "ARMeasure-phone-bbbbbbb.apk", b"mid" * 100, 300)
    mk(d, "ARMeasure-debug-506a405.apk", bytes(range(256)) * 40, 100)
    mk(d, "ARMeasure-debug-fresh12.apk", b"x" * 50, 0)          # still being written
    mk(d, "ARMeasure-debug-cccccc1.apk.part", b"p" * 50, 900)
    mk(d, "ARMeasure-debug-empty01.apk", b"", 900)
    mk(d, "LeaveOnTime-debug-92fd593.apk", b"lot" * 10, 200)
    mk(d, "LeaveOnTime-debug-1234567.apk", b"lot2" * 10, 400)
    mk(d, "notes.txt", b"nope", 10)
    return d


def make_client(tmp_path, apk_dir, **kw):
    app = create_app(tmp_path / "data", token=TOKEN, apk_dir=apk_dir, **kw)
    return TestClient(app)


@pytest.fixture()
def dev(tmp_path, apks):
    with make_client(tmp_path, apks) as c:
        yield c


def test_apk_listing_newest_per_package(dev, apks):
    r = dev.get("/v1/dev/apk", params={"package": "com.example.arruler"}, headers=AUTH)
    assert r.status_code == 200
    j = r.json()
    assert j["url"] == "/v1/dev/apk/ARMeasure-debug-506a405.apk"
    assert j["commit"] == "506a405" and j["size"] == 256 * 40
    assert j["sha256"] == hashlib.sha256(bytes(range(256)) * 40).hexdigest()
    assert j["versionCode"] >= 1 and isinstance(j["versionName"], str)
    r = dev.get("/v1/dev/apk", params={"package": "com.reyses.leaveontime"}, headers=AUTH)
    assert r.json()["url"] == "/v1/dev/apk/LeaveOnTime-debug-92fd593.apk" and r.json()["commit"] == "92fd593"
    assert dev.get("/v1/dev/apk", params={"package": "com.other"}, headers=AUTH).status_code == 404
    assert dev.get("/v1/dev/apk", headers=AUTH).status_code == 404
    assert dev.get("/v1/dev/apk", params={"package": "com.example.arruler"}).status_code == 401


def test_commit_parsing_and_empty_dir(tmp_path):
    assert devlink.commit_of("ARMeasure-debug-506a405.apk") == "506a405"
    assert devlink.commit_of("ARMeasure-debug.apk") == ""
    assert devlink.commit_of("ARMeasure-debug-NOTHEX1.apk") == ""
    empty = tmp_path / "e"
    empty.mkdir()
    with make_client(tmp_path, empty) as c:
        assert c.get("/v1/dev/apk", params={"package": "com.example.arruler"}, headers=AUTH).status_code == 404
    with make_client(tmp_path, tmp_path / "missing") as c:
        assert c.get("/v1/dev/apk", params={"package": "com.example.arruler"}, headers=AUTH).status_code == 404


def test_sha_cached_by_mtime(apks):
    p = apks / "ARMeasure-debug-506a405.apk"
    h1 = devlink.sha256_of(p)
    p.write_bytes(b"changed")
    os.utime(p, (time.time() - 50, time.time() - 50))
    assert devlink.sha256_of(p) == hashlib.sha256(b"changed").hexdigest() != h1


def test_download_and_range(dev):
    url = "/v1/dev/apk/ARMeasure-debug-506a405.apk"
    data = bytes(range(256)) * 40
    r = dev.get(url, headers=AUTH)
    assert r.status_code == 200 and r.content == data
    assert r.headers["content-type"] == "application/vnd.android.package-archive"
    assert int(r.headers["content-length"]) == len(data)
    r = dev.get(url, headers={**AUTH, "Range": "bytes=100-199"})
    assert r.status_code == 206 and r.content == data[100:200]
    assert r.headers["content-range"] == f"bytes 100-199/{len(data)}"
    r = dev.get(url, headers={**AUTH, "Range": "bytes=10000-"})
    assert r.status_code == 206 and r.content == data[10000:]
    assert dev.get(url).status_code == 401


@pytest.mark.parametrize("name", ["..%2Fsecret.txt", "%2e%2e%2f%2e%2e%2fconfig.json", "..%5Cx.apk", "notes.txt",
                                  "ARMeasure-debug-fresh12.apk", "ARMeasure-debug-cccccc1.apk.part",
                                  "ARMeasure-debug-empty01.apk", "ARMeasure-nothere.apk", "..", "ARMeasure-*.apk",
                                  "%2FWindows%2Fwin.ini"])
def test_download_rejects(dev, tmp_path, name):
    (tmp_path / "secret.txt").write_text("s")
    r = dev.get(f"/v1/dev/apk/{name}", headers=AUTH)
    assert r.status_code in (404, 400) and b"secret" not in r.content


def test_logs_upload(dev, tmp_path):
    def post(kind, content=b"line\n", device="Google Pixel 11 Pro", **kw):
        return dev.post("/v1/dev/logs", headers=AUTH, data={"device": device, "app": "com.example.arruler",
                        "commit": "506a405", "kind": kind},
                        files={"file": ("logs-506a405-20261003-101500.txt", content, "text/plain")}, **kw)

    r = post("logs", b"hello")
    assert r.status_code == 200
    lid = r.json()["id"]
    assert lid.startswith("L-") and len(lid.split("-")) == 4
    files = list((tmp_path / "data" / "devlogs").glob("*/*.txt"))
    assert len(files) == 1 and files[0].name == f"{lid}-logs-Google_Pixel_11_Pro.txt"
    assert files[0].read_bytes() == b"hello"
    assert post("crash").json()["id"].startswith("C-") and post("diagnostics").json()["id"].startswith("D-")
    assert post("weird").status_code == 400
    assert post("logs", device="../../evil\\x").status_code == 200
    assert all(f.resolve().is_relative_to((tmp_path / "data" / "devlogs").resolve())
               for f in (tmp_path / "data" / "devlogs").glob("**/*"))
    r = dev.post("/v1/dev/logs", headers=AUTH, data={"kind": "logs"})
    assert r.status_code == 400
    r = dev.post("/v1/dev/logs", data={"kind": "logs"}, files={"file": ("a.txt", b"x")})
    assert r.status_code == 401


def test_logs_size_cap(dev):
    dev.app.state.max_dev_log = 1000
    big = b"x" * 5000
    r = dev.post("/v1/dev/logs", headers=AUTH, data={"kind": "logs"}, files={"file": ("a.txt", big)})
    assert r.status_code == 413
    ok = dev.post("/v1/dev/logs", headers=AUTH, data={"kind": "logs"}, files={"file": ("a.txt", b"x" * 100)})
    assert ok.status_code == 200


def test_logs_keep_newest(tmp_path):
    for i in range(7):
        devlink.store_log(tmp_path, "logs", "d", b"x", keep=5)
    assert len(list((tmp_path / "devlogs").glob("*/*.txt"))) == 5


# ---- pairing urls ----
TS = {"ip": "100.101.102.103", "name": "rxmoi", "fqdn": "rxmoi.tail1234.ts.net"}


def test_pairing_urls_order():
    u = net.pairing_urls(48310, "192.168.0.247", "https://x.trycloudflare.com", TS)
    assert u == ["http://rxmoi:48310", "http://100.101.102.103:48310", "http://192.168.0.247:48310",
                 "https://x.trycloudflare.com"]
    assert net.pairing_urls(48310, "192.168.0.247", None, None) == ["http://192.168.0.247:48310"]
    assert net.pairing_urls(48310, "192.168.0.247", "https://t", None) == ["http://192.168.0.247:48310", "https://t"]
    assert net.pairing_urls(48310, None, None, {"ip": "100.1.2.3", "name": None, "fqdn": None}) == [
        "http://100.1.2.3:48310"]
    j = json.loads(pairing.payload("ignored", "tok", "Rxmoi", u))
    assert j["url"] == u[0] and j["urls"] == u and j["token"] == "tok" and j["v"] == 1
    assert json.loads(pairing.payload("http://a:1", "tok", "n")) == {"v": 1, "url": "http://a:1", "token": "tok",
                                                                    "name": "n"}


def test_tailscale_info_mocked(tmp_path, monkeypatch):
    exe = tmp_path / "tailscale.exe"
    exe.write_text("")
    status = json.dumps({"Self": {"DNSName": "rxmoi.tail1234.ts.net."}})
    outs = {("ip", "-4"): "100.101.102.103\n", ("status", "--json"): status}
    monkeypatch.setattr(net, "_run_ts", lambda e, *a: outs.get(a))
    assert net.tailscale_info(exe) == TS
    outs[("status", "--json")] = None                       # status unavailable -> IP only
    assert net.tailscale_info(exe) == {"ip": "100.101.102.103", "name": None, "fqdn": None}
    outs[("ip", "-4")] = None                               # stopped / not logged in
    assert net.tailscale_info(exe) is None
    outs[("ip", "-4")] = "192.168.0.5\n"                    # not a CGNAT address
    assert net.tailscale_info(exe) is None
    assert net.tailscale_info(tmp_path / "absent.exe") is None


# ---- bind filtering ----
def test_bind_addresses():
    assert net.bind_addresses("auto", "192.168.0.247", TS) == ["127.0.0.1", "100.101.102.103", "192.168.0.247"]
    assert net.bind_addresses("auto", "192.168.0.247", None) == ["127.0.0.1", "192.168.0.247"]
    assert net.bind_addresses("auto", None, None) == ["127.0.0.1"]
    assert net.bind_addresses("0.0.0.0", "192.168.0.247", TS) == ["0.0.0.0"]


def test_address_filter(tmp_path):
    app = create_app(tmp_path / "data", token=TOKEN, allowed_hosts={"127.0.0.1", "100.101.102.103"})
    with TestClient(app, base_url="http://100.101.102.103") as c:
        # TestClient reports server=("100.101.102.103", 80)
        assert c.get("/v1/ping", headers=AUTH).status_code == 200
    with TestClient(app, base_url="http://10.9.9.9") as c:
        r = c.get("/v1/ping", headers=AUTH)
        assert r.status_code == 403 and r.json()["error"]["code"] == "forbidden"
    open_app = create_app(tmp_path / "data2", token=TOKEN)
    with TestClient(open_app, base_url="http://10.9.9.9") as c:
        assert c.get("/v1/ping", headers=AUTH).status_code == 200


# ---- lockout, logging, forwarded IPs ----
def lines(p: Path):
    return [json.loads(x) for x in p.read_text(encoding="utf-8").splitlines()]


def test_lockout_and_failure_log(tmp_path):
    clock = [1000.0]
    lock = Lockout(clock=lambda: clock[0])
    app = create_app(tmp_path / "data", token=TOKEN, lockout=lock)
    bad = {"Authorization": "Bearer wrong-secret-value"}
    with TestClient(app, client=("203.0.113.9", 5555)) as c:
        for _ in range(10):
            assert c.get("/v1/ping", headers=bad).status_code == 401
            clock[0] += 1
        r = c.get("/v1/ping", headers=AUTH)               # even the right token is refused now
        assert r.status_code == 429 and "retry-after" in r.headers
        clock[0] += 14 * 60
        assert c.get("/v1/ping", headers=AUTH).status_code == 429
        clock[0] += 2 * 60
        assert c.get("/v1/ping", headers=AUTH).status_code == 200
    with TestClient(app, client=("203.0.113.10", 5555)) as other:     # another IP is unaffected
        assert other.get("/v1/ping", headers=AUTH).status_code == 200
    fails = lines(tmp_path / "data" / "auth_failures.jsonl")
    assert len(fails) == 10 and fails[-1]["locked"] is True and fails[0]["locked"] is False
    assert fails[0]["ip"] == "203.0.113.9" and fails[0]["reason"] == "invalid"
    raw = (tmp_path / "data" / "auth_failures.jsonl").read_text() + (tmp_path / "data" / "access.log").read_text()
    assert "wrong-secret-value" not in raw and TOKEN not in raw


def test_lockout_window_expires():
    clock = [0.0]
    lk = Lockout(failures=3, window=10, duration=30, clock=lambda: clock[0])
    lk.fail("a"); clock[0] += 6; lk.fail("a"); clock[0] += 6
    assert lk.fail("a") is False and lk.locked("a") == 0     # first failure aged out of the window
    clock[0] += 1
    assert lk.fail("a") is True and 29 < lk.locked("a") <= 30      # 6 s, 12 s, 13 s: three inside 10 s


def test_missing_header_reason_and_access_log(tmp_path):
    app = create_app(tmp_path / "data", token=TOKEN)
    with TestClient(app, client=("198.51.100.4", 1)) as c:
        c.get("/v1/ping")
        c.get("/v1/ping", headers=AUTH)
    acc = lines(tmp_path / "data" / "access.log")
    assert [(a["method"], a["path"], a["status"], a["ip"]) for a in acc] == [
        ("GET", "/v1/ping", 401, "198.51.100.4"), ("GET", "/v1/ping", 200, "198.51.100.4")]
    assert "ts" in acc[0]
    assert lines(tmp_path / "data" / "auth_failures.jsonl")[0]["reason"] == "missing"


def test_forwarded_for_only_from_loopback(tmp_path):
    app = create_app(tmp_path / "data", token=TOKEN)
    hdr = {**AUTH, "X-Forwarded-For": "7.7.7.7, 10.0.0.1"}
    with TestClient(app, client=("127.0.0.1", 1)) as c:                 # cloudflared
        c.get("/v1/ping", headers=hdr)
        c.get("/v1/ping", headers={**AUTH, "CF-Connecting-IP": "8.8.4.4", "X-Forwarded-For": "7.7.7.7"})
        c.get("/v1/ping", headers={**AUTH, "X-Forwarded-For": "not-an-ip"})
    with TestClient(app, client=("192.168.0.50", 1)) as c:              # LAN peer cannot spoof
        c.get("/v1/ping", headers=hdr)
    acc = lines(tmp_path / "data" / "access.log")
    assert [a["ip"] for a in acc] == ["7.7.7.7", "8.8.4.4", "127.0.0.1", "192.168.0.50"]
    assert acc[0]["peer"] == "127.0.0.1" and "peer" not in acc[3]


def test_lockout_keys_on_forwarded_ip(tmp_path):
    app = create_app(tmp_path / "data", token=TOKEN)
    with TestClient(app, client=("127.0.0.1", 1)) as c:
        for _ in range(10):
            c.get("/v1/ping", headers={"CF-Connecting-IP": "9.9.9.9", "Authorization": "Bearer x"})
        assert c.get("/v1/ping", headers={**AUTH, "CF-Connecting-IP": "9.9.9.9"}).status_code == 429
        assert c.get("/v1/ping", headers={**AUTH, "CF-Connecting-IP": "9.9.9.8"}).status_code == 200
        assert c.get("/v1/ping", headers=AUTH).status_code == 200      # the tunnel itself is not locked


def test_jsonl_rotation(tmp_path):
    lg = JsonlLog(tmp_path / "x.log", max_bytes=200, backups=2)
    for i in range(40):
        lg.write({"i": i, "pad": "p" * 20})
    assert (tmp_path / "x.log.1").exists() and (tmp_path / "x.log.2").exists() and not (tmp_path / "x.log.3").exists()
