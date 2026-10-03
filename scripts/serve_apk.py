"""Serve a folder of APKs for phone sideloading (behind a cloudflared quick tunnel).

Python's built-in http.server ignores Range requests and labels .apk as
application/octet-stream. Chrome on Android downloads large files in ranges and
resumes with them, so a dropped packet killed the whole download. This server
answers single-range requests with 206 Partial Content, advertises
Accept-Ranges, and sends the Android package MIME type.

Usage: python scripts/serve_apk.py D:\\APK [port]   (binds 127.0.0.1 only)
"""

import os
import re
import sys
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

APK_MIME = "application/vnd.android.package-archive"
RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")


class RangeHandler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".apk": APK_MIME}

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        path = getattr(self, "_cur_path", "")
        if path.lower().endswith(".apk") and os.path.isfile(path):
            self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(path)}"')
        super().end_headers()

    def send_head(self):
        path = self.translate_path(self.path)
        self._cur_path = path
        if os.path.isdir(path) or "Range" not in self.headers:
            return self._send_full(path)
        match = RANGE_RE.match(self.headers["Range"].strip())
        if not match or not os.path.isfile(path):
            return self._send_full(path)
        size = os.path.getsize(path)
        first, last = match.groups()
        if first == "":
            if last == "":
                return self._send_full(path)
            start, end = max(0, size - int(last)), size - 1
        else:
            start, end = int(first), int(last) if last else size - 1
        end = min(end, size - 1)
        if start > end or start >= size:
            self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return None
        f = open(path, "rb")
        f.seek(start)
        self.send_response(HTTPStatus.PARTIAL_CONTENT)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def _send_full(self, path):
        return super().send_head()

    def copyfile(self, source, outputfile):
        remaining = getattr(self, "_remaining", None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        while remaining > 0:
            chunk = source.read(min(256 * 1024, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)
        self._remaining = None


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 48300
    server = ThreadingHTTPServer(("127.0.0.1", port), partial(RangeHandler, directory=root))
    print(f"serving {root} on http://127.0.0.1:{port} (Range + APK MIME)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
