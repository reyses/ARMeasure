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
