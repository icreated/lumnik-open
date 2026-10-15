#!/usr/bin/env bash
# loadtest/search/test-lib.sh — the convergence predicate against a fake operations door (#750).
set -euo pipefail
cd "$(dirname "$0")"; source ./lib.sh
PORT=18999; STATE="$(mktemp)"
python3 - "$PORT" "$STATE" <<'PYEOF' &
import http.server, json, sys
port, state = int(sys.argv[1]), sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        import os
        if os.path.exists(state + '.tok') and self.headers.get('Authorization') != 'Bearer ' + open(state + '.tok').read().strip():
            self.send_response(401); self.end_headers(); return   # an expired / unknown token
        name = self.path.split('/index/')[1].split('/')[0]
        d = json.load(open(state)).get(name, {})
        body = json.dumps(d).encode(); self.send_response(200); self.send_header('Content-Type','application/json')
        self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', port), H).serve_forever()
PYEOF
SRV=$!; trap 'kill $SRV 2>/dev/null; wait $SRV 2>/dev/null; rm -f "$STATE"' EXIT; sleep 0.5
set_state() { echo "$1" > "$STATE"; }
U="http://127.0.0.1:$PORT"; ok=0; fail=0
expect_red()   { if sz_converged "$U" t "$1" a b >/dev/null 2>&1; then echo "FAIL (should be red): $2"; fail=$((fail+1)); else ok=$((ok+1)); fi; }
expect_green() { if sz_converged "$U" t "$1" a b >/dev/null 2>&1; then ok=$((ok+1)); else echo "FAIL (should be green): $2"; fail=$((fail+1)); fi; }
idx() { echo "{\"lagSeconds\":$1,\"documentCount\":$2,\"documentCountStale\":$3}"; }

set_state "{\"a\":$(idx 0 100 false),\"b\":$(idx 0 100 false)}";  expect_green 100 "all four legs true"
set_state "{\"a\":$(idx 5 100 false),\"b\":$(idx 0 100 false)}";  expect_red 100 "one index lagging"
set_state "{\"a\":$(idx 0 99 false),\"b\":$(idx 0 100 false)}";   expect_red 100 "count short"
set_state "{\"a\":$(idx 0 100 true),\"b\":$(idx 0 100 false)}";   expect_red 100 "stale count"
set_state "{\"a\":$(idx 0 0 false),\"b\":$(idx 0 0 false)}";      expect_red 100 "zero lag before the marks exist (nothing projected yet)"
set_state "{\"a\":$(idx 0 100 false)}";                            expect_red 100 "an index the door does not know"
[ "$(sz_max_lag "$U" t a b)" = "0" ] || { echo "FAIL sz_max_lag"; fail=$((fail+1)); }
set_state "{\"a\":$(idx 7 1 false),\"b\":$(idx 3 1 false)}";      [ "$(sz_max_lag "$U" t a b)" = "7" ] || { echo "FAIL sz_max_lag max"; fail=$((fail+1)); }
set_state "{\"a\":$(idx 0 1 false),\"b\":$(idx 0 1 false)}"
sz_wait_converged 2 "$U" t 100 a b >/dev/null 2>&1 && { echo "FAIL wait must time out red"; fail=$((fail+1)); } || ok=$((ok+1))

# A token that expires DURING a long wait (the realm's lives one hour; a 50k base takes ~100 minutes): the wait must
# mint a fresh one, not poll with the stale one until the timeout.
echo tokA > "$STATE.tok"; echo tokA > "$STATE.minted"
set_state "{\"a\":$(idx 5 100 false),\"b\":$(idx 5 100 false)}"
( sleep 3; echo tokB > "$STATE.tok"; echo tokB > "$STATE.minted"; sleep 2; echo "{\"a\":$(idx 0 100 false),\"b\":$(idx 0 100 false)}" > "$STATE" ) &
FLIP=$!
SZ_TOKEN_CMD="cat $STATE.minted" SZ_TOKEN_REFRESH_S=1 sz_wait_converged 20 "$U" tokA 100 a b >/dev/null 2>&1 && ok=$((ok+1)) || { echo "FAIL: the wait did not refresh an expired token"; fail=$((fail+1)); }
wait "$FLIP"
# ... and it must not hammer the IdP: with the default refresh interval a short wait mints ONCE, not once per poll
rm -f "$STATE.tok"; : > "$STATE.mints"
set_state "{\"a\":$(idx 5 100 false),\"b\":$(idx 5 100 false)}"
count_mint() { echo m >> "$STATE.mints"; echo t; }
SZ_TOKEN_CMD=count_mint sz_wait_converged 5 "$U" t0 100 a b >/dev/null 2>&1 || true
mints="$(wc -l < "$STATE.mints" | tr -d ' ')"
if [[ "$mints" == 1 ]]; then ok=$((ok+1)); else echo "FAIL: $mints token mints in a 5 s wait (want 1)"; fail=$((fail+1)); fi
rm -f "$STATE.mints"
rm -f "$STATE.tok" "$STATE.minted"
echo "predicate tests: $ok ok, $fail failed"; [ "$fail" -eq 0 ]
