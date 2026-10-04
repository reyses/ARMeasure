"""Pairing QR: JSON {"v":1,"url","token","name"} as ASCII in the console and a PNG in the default viewer."""
from __future__ import annotations

import io
import json
import os
import sys
from pathlib import Path

import qrcode


def payload(url: str, token: str, name: str, urls: list[str] | None = None) -> str:
    """"urls" (ordered candidates) is added when given; "url" stays for old phones (= first entry)."""
    d: dict = {"v": 1, "url": urls[0] if urls else url}
    if urls:
        d["urls"] = urls
    d.update(token=token, name=name)
    return json.dumps(d, separators=(",", ":"))


def show(url: str, token: str, name: str, data_dir: Path, open_png: bool = True,
         urls: list[str] | None = None) -> Path:
    text = payload(url, token, name, urls)
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
