"""Pairing QR: JSON {"v":1,"url","token","name"} as ASCII in the console and a PNG in the default viewer."""
from __future__ import annotations

import io
import json
import os
import sys
from pathlib import Path

import qrcode


def payload(url: str, token: str, name: str) -> str:
    return json.dumps({"v": 1, "url": url, "token": token, "name": name}, separators=(",", ":"))


def show(url: str, token: str, name: str, data_dir: Path, open_png: bool = True) -> Path:
    text = payload(url, token, name)
    print("\nPairing payload (scan the QR in the ARMeasure app):\n" + text + "\n")
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
    qr.add_data(text)
    qr.make(fit=True)
    buf = io.StringIO()
    qr.print_ascii(out=buf, invert=True)
    try:
        print(buf.getvalue())
    except UnicodeEncodeError:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        print(buf.getvalue())
    png = data_dir / "pairing.png"
    qr.make_image(fill_color="black", back_color="white").save(str(png))
    if open_png and hasattr(os, "startfile"):
        try:
            os.startfile(str(png))  # noqa: S606 - opens the default image viewer
        except OSError as e:
            print(f"(could not open the PNG viewer: {e}; file is {png})")
    return png
