"""Minimal OpenAI-compatible mock server for testing the AI subtitle translation UI.

Serves POST /v1/chat/completions and GET /v1/models. Echoes a deterministic
pseudo-translation of the requested lines so progress is observable, and adds a
configurable per-request delay so the progress dialog stays on screen long enough
to screenshot.
"""
import json
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

DELAY_S = float(sys.argv[2]) if len(sys.argv) > 2 else 1.5
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
REQ_LOG = sys.argv[3] if len(sys.argv) > 3 else "mock_requests.log"


def fake_translate(text: str, target: str) -> str:
    # Prefix with the requested target language so the caller can tell WHICH
    # language produced a given translation (needed to verify re-translation).
    return "[%s] %s" % (target, text)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] " + fmt % args + "\n")

    def _json(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._json(200, {"data": [{"id": "mock-model"}]})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")
        try:
            req = json.loads(raw)
        except Exception:
            self._json(400, {"error": "bad json"})
            return

        user_text = ""
        system_text = ""
        for m in req.get("messages", []):
            if m.get("role") == "user":
                user_text = m.get("content", "")
            elif m.get("role") == "system":
                system_text = m.get("content", "")

        # SYSTEM_TEMPLATE 把目标语言渲染在 "翻译成%s。" 里；用它区分语言
        tm = re.search(r"翻译成(.+?)。", system_text)
        target = tm.group(1) if tm else "?"

        # Log what the app actually SENT, so the test can assert whether a
        # re-translation was fed the original text or a previous translation.
        with open(REQ_LOG, "a", encoding="utf-8") as fh:
            fh.write("=== target=%s ===\n%s\n" % (target, user_text))

        # Lines look like "12|原文"; translate the part after the first '|'.
        out_lines = []
        for line in user_text.splitlines():
            m = re.match(r"^\s*(\d+)\|(.*)$", line)
            if m:
                out_lines.append("%s|%s" % (m.group(1), fake_translate(m.group(2), target)))

        time.sleep(DELAY_S)
        self._json(200, {
            "choices": [{"message": {"role": "assistant",
                                     "content": "\n".join(out_lines)}}]
        })


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
