"""LAN IPv4 detection and GPU info."""
from __future__ import annotations

import ipaddress
import re
import socket
import subprocess
from functools import lru_cache

import psutil

VIRTUAL = re.compile(r"vethernet|vmware|virtualbox|vbox|hyper-v|wsl|docker|loopback|bluetooth|tailscale|"
                     r"zerotier|tap-|tun|vpn|npcap|pseudo|virtual|host-only|hamachi|cloudflare|wintun|"
                     r"teredo|isatap", re.I)


def _adapter_of(ip: str) -> str | None:
    for name, addrs in psutil.net_if_addrs().items():
        for a in addrs:
            if a.family == socket.AF_INET and a.address == ip:
                return name
    return None


def _usable(ip: str, name: str | None) -> bool:
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if a.is_loopback or a.is_link_local or a.is_unspecified or a.is_multicast:
        return False
    if name is None or VIRTUAL.search(name):
        return False
    st = psutil.net_if_stats().get(name)
    return bool(st and st.isup)


def lan_ipv4() -> str:
    """IPv4 of the physical adapter that carries the default route; else the best physical candidate."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("203.0.113.1", 9))   # no packet is sent; selects the outbound interface
        ip = s.getsockname()[0]
        s.close()
        if _usable(ip, _adapter_of(ip)):
            return ip
    except OSError:
        pass
    cands = []
    for name, addrs in psutil.net_if_addrs().items():
        for a in addrs:
            if a.family == socket.AF_INET and _usable(a.address, name):
                private = ipaddress.ip_address(a.address).is_private
                cands.append((not private, name, a.address))
    if cands:
        return sorted(cands)[0][2]
    raise RuntimeError("no active physical IPv4 adapter found")


@lru_cache(maxsize=1)
def gpu_info() -> tuple[str | None, bool]:
    try:
        r = subprocess.run(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                           capture_output=True, text=True, timeout=10,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        name = r.stdout.strip().splitlines()[0].strip() if r.returncode == 0 and r.stdout.strip() else None
    except (OSError, subprocess.TimeoutExpired):
        name = None
    return name, name is not None


def _run_ts(exe, *args) -> str | None:
    try:
        r = subprocess.run([str(exe), *args], capture_output=True, text=True, timeout=8,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.stdout if r.returncode == 0 else None


def tailscale_info(exe=None) -> dict | None:
    """{"ip": "100.x.y.z", "name": "rxmoi", "fqdn": "rxmoi.tailnet.ts.net"} or None when Tailscale is absent,
    stopped or not logged in. "name"/"fqdn" may be None when MagicDNS has no name."""
    import json
    from . import config
    exe = exe or config.TAILSCALE
    if not exe or not exe.exists():
        return None
    out = _run_ts(exe, "ip", "-4")
    ip = None
    for line in (out or "").split():
        try:
            a = ipaddress.ip_address(line.strip())
        except ValueError:
            continue
        if a.version == 4 and a in ipaddress.ip_network("100.64.0.0/10"):
            ip = str(a)
            break
    if ip is None:
        return None
    name = fqdn = None
    st = _run_ts(exe, "status", "--json")
    try:
        dns = ((json.loads(st or "{}").get("Self") or {}).get("DNSName") or "").rstrip(".")
    except (ValueError, AttributeError):
        dns = ""
    if dns:
        fqdn, name = dns, dns.split(".")[0]
    return {"ip": ip, "name": name, "fqdn": fqdn}


def pairing_urls(port: int, lan_ip: str | None, tunnel_url: str | None, ts: dict | None) -> list[str]:
    """Ordered candidates [tailnet..., lan, tunnel]; the phone tries them in this order."""
    urls: list[str] = []
    if ts:
        if ts.get("name"):
            urls.append(f"http://{ts['name']}:{port}")
        urls.append(f"http://{ts['ip']}:{port}")
    if lan_ip:
        urls.append(f"http://{lan_ip}:{port}")
    if tunnel_url:
        urls.append(tunnel_url)
    return list(dict.fromkeys(urls))


def bind_addresses(mode: str, lan_ip: str | None, ts: dict | None) -> list[str]:
    """'auto' -> loopback + Tailscale IP + LAN IP (de-duplicated); anything else is used verbatim."""
    if mode != "auto":
        return [mode]
    out = ["127.0.0.1"]
    for ip in (ts["ip"] if ts else None, lan_ip):
        if ip and ip not in out:
            out.append(ip)
    return out
