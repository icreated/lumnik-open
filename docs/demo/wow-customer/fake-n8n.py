#!/usr/bin/env python3
"""A tiny webhook receiver standing in for n8n/Make/Zapier: prints every delivery it gets.
Subscribe a lumnik webhook_endpoint to decision.* pointing at http://host.docker.internal:9099/hook
(the hub runs in Docker; host.docker.internal reaches this process on the host)."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            pretty = json.dumps(json.loads(body), indent=2, ensure_ascii=False)
        except Exception:
            pretty = body.decode(errors="replace")
        print(f"\n[fake-n8n] POST {self.path} received:\n{pretty}", flush=True)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *_):  # quiet the default access log
        pass

if __name__ == "__main__":
    print("[fake-n8n] listening on :9099 — waiting for decisions…", flush=True)
    HTTPServer(("0.0.0.0", 9099), Hook).serve_forever()
