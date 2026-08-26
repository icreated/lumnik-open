#!/usr/bin/env python3
"""A 40-line stand-in for Siebel — "you don't have Siebel data, here it is".

Speaks just enough of the CustomerQueryPage SOAP dialect for lumnik's `kind: soap`
connector: POST anything, get back an Envelope with a ListOfCustomer page and LastPage.

It deliberately holds only C-100 and C-200 — C-300 lives only in the CSV export, so the
fusion can show a CSV-only customer. C-200's name ('Marie') disagrees with the CSV
('MARIE A.') so the fused entity surfaces a real cross-source conflict.

    python3 fake-siebel.py            # serves on http://localhost:8077/eai
    python3 fake-siebel.py 9000       # ...or any port you pass

No dependencies — Python 3 standard library only.
"""
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

CUSTOMERS = [
    # (code, name, segment) — segment is GOLD/SILVER/BRONZE; there is NO 'PLATINUM' (the domain guard demo)
    ("C-100", "Jean Dupont", "GOLD"),    # agrees with the CSV → no conflict, just gap-filled email/city
    ("C-200", "Marie", "SILVER"),        # disagrees with the CSV's 'MARIE A.' → a surfaced conflict
]

ENVELOPE = (
    '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>'
    "<Out><ListOfCustomer>{rows}</ListOfCustomer><LastPage>true</LastPage></Out>"
    "</soap:Body></soap:Envelope>"
)


class Siebel(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))  # drain the request body
        rows = "".join(
            f"<Customer><CustCode>{code}</CustCode><Name>{name}</Name>"
            f"<Segment>{segment}</Segment></Customer>"
            for code, name, segment in CUSTOMERS
        )
        body = ENVELOPE.format(rows=rows).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/xml")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):  # quiet
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8077
    print(f"fake-siebel listening on http://localhost:{port}/eai  (POST → CustomerQueryPage)")
    # All interfaces, like fake-n8n: on a Linux host the hub container reaches the host
    # through the docker bridge IP, never loopback — a localhost bind makes the README's
    # host.docker.internal instruction silently unreachable there (Docker Desktop masked it).
    HTTPServer(("", port), Siebel).serve_forever()
