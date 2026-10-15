#!/usr/bin/env bash
# Search sizing, phases A (search alone) / B (ingest alone) / C (search during ingest) per tier (#750).
# B and C each start from the SAME snapshot at N and ingest an equivalent, key-disjoint Δ range.
set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)/.."
S=loadtest/search
TIERS="10000,50000,200000"; RATE=20; DURATION=60s; MIN_FREE_MB="${MIN_FREE_MB:-15000}"; CONV_TIMEOUT=1800
while [[ $# -gt 0 ]]; do case "$1" in
  --tiers) TIERS="$2"; shift 2 ;; --rate) RATE="$2"; shift 2 ;; --duration) DURATION="$2"; shift 2 ;;
  *) echo "unknown option $1" >&2; exit 2 ;; esac; done
for t in ${TIERS//,/ }; do case "$t" in 10000|50000|200000) ;; *) echo "unknown tier $t (allowed: 10000, 50000, 200000)" >&2; exit 2 ;; esac; done

[[ -f "$S/.run/env" ]] || { echo "no stack: run 'bash $S/stack.sh up' first" >&2; exit 1; }
source "$S/.run/env"; source "$S/lib.sh"
STAMP="$(date +%Y%m%d-%H%M%S)"; OUT="$S/results/$STAMP"; mkdir -p "$OUT"
ENT=(sz_exact sz_text sz_fuzzy sz_prefix)
python3 "$S/gen_corpus.py" queries --out "$S/queries.json"
token() { bash "$S/stack.sh" token; }
SZ_TOKEN_CMD=token   # sz_wait_converged mints a fresh token while it waits: a 50k/200k base outlives the 1 h token
# The substrate is append-only: rows a tier ingests stay. Every tier therefore starts from an EMPTY-fixture snapshot
# (tables created, manifests applied, indexes built at zero documents), or the 50k tier would find 10k+1k+... rows and never reach its count.
# A header-only CSV creates no table, so the smallest honest starting state is ONE seed row per entity (key range "seed",
# disjoint from base/1/2). Every expected count below is therefore N + SEED_ROWS.
SEED_ROWS=1
LIVE="$S/.run/data/live"; mkdir -p "$LIVE"; rm -f "$LIVE"/*.csv     # the connectors read this directory, in lexical order
python3 "$S/gen_corpus.py" csv --rows "$SEED_ROWS" --range seed --out "$LIVE/0-seed.csv"
bash "$S/fixture.sh" init      # connectors -> runs (tables exist) -> entities -> search indexes
sz_wait_converged "$CONV_TIMEOUT" "$SZ_BASE_URL" "$(token)" "$SEED_ROWS" "${ENT[@]}" || { echo "seed fixture did not converge at $SEED_ROWS document" >&2; exit 1; }
bash "$S/snapshot.sh" take seed         # only now: tables exist, indexes built, documentCount == SEED_ROWS and not stale
FAILED=0
K6NAME=""
cleanup() {  # an aborted run must not leave a sampler minting tokens or a k6 container loading the stack
  local p; for p in $(jobs -p); do kill "$p" 2>/dev/null || true; done
  [[ -z "$K6NAME" ]] || docker kill "$K6NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
# what produced these numbers: the commit, and the hub image actually running
commit_stamp() {  # best effort: a source tarball or the extracted open edition has no git checkout
  local h; h="$(git rev-parse HEAD 2>/dev/null)" || { echo "unknown (not a git checkout)"; return 0; }
  if git diff --quiet HEAD -- loadtest docs 2>/dev/null; then echo "$h"; else echo "$h (+ uncommitted changes)"; fi
}
{ echo "commit: $(commit_stamp)"
  echo "hub image: $(docker image inspect lumnik-hub:sizing --format '{{.Id}} created {{.Created}}' 2>/dev/null || echo unknown)"
  echo "docker: $(docker version --format '{{.Server.Version}}') on $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes')"
  echo "rate: $RATE req/s, phase A: $DURATION, tiers: $TIERS, MIN_FREE_MB: $MIN_FREE_MB"; } > "$OUT/version.txt"

free_mb() { bash "$S/stack.sh" free-space-mb; }
record_disk() { printf '{"free_mb":%s,"db_mb":%s}' "$(free_mb)" "$(bash "$S/stack.sh" db-size-mb)"; }

k6_run() {  # <duration> <phase A|C> <summary-file-name> [container-name] — a foreground docker run; background it with &
  K6_CONTAINER="${4:-}" K6_DATA_DIR="$PWD/$S" K6_OUT_DIR="$PWD/$OUT" BASE_URL="$SZ_BASE_URL" KC_URL="$SZ_KC_URL" K6_USER=integrator \
  K6_PASSWORD="$(grep '^INTEGRATOR_PASSWORD=' "$S/.run/stack.env" | cut -d= -f2-)" \
  RATE="$RATE" DURATION="$1" PHASE="$2" SUMMARY_OUT="/out/$3" P95_MS=3600000 ./loadtest/run.sh search-mix.js
}

# k6's own thresholds stay on for validity (429, failures, hit share) but latency is an OBSERVATION here: a p95 over the
# budget is the finding, not a broken run. k6 gets an unreachable P95_MS; the budget is applied below and in summary.md.
BUDGET_P95_MS="${BUDGET_P95_MS:-500}"; MIN_HIT_SHARE="${MIN_HIT_SHARE:-0.95}"
check_search_summary() {  # <summary-file> — red unless the file exists and proves real load: requests, no 429, few failures, every mode answered
  python3 - "$1" "$MIN_HIT_SHARE" <<'PYCHK'
import json, sys
path, floor = sys.argv[1], float(sys.argv[2])
try: d = json.load(open(path))
except (OSError, ValueError) as e: sys.exit(f"summary missing or unreadable: {path} ({e})")
bad = []
if not d.get("requests"): bad.append("no requests were made")
if d.get("http_429"): bad.append(f"http_429={d['http_429']}")
if d.get("failed_rate") is None or d["failed_rate"] >= 0.01: bad.append(f"failed_rate={d.get('failed_rate')}")
if d.get("expect_share_exact") is None or d["expect_share_exact"] <= 0.99:
    bad.append(f"exact mode returned its expected row for only {d.get('expect_share_exact')} of its queries")
for mode in ("exact", "text", "fuzzy", "prefix"):
    m = d.get("modes", {}).get(mode)
    if not m or m.get("p95") is None: bad.append(f"{mode}: no p95"); continue
    if m.get("hit_share") is None or m["hit_share"] <= floor: bad.append(f"{mode}: hit_share={m.get('hit_share')}")
if bad: sys.exit("; ".join(bad))
PYCHK
}

phase_A() {  # <tier>
  rm -f "$OUT/$1-A-search.json"
  k6_run "$DURATION" A "$1-A-search.json" || true     # k6's exit status is not the verdict: the validator below is
  check_search_summary "$OUT/$1-A-search.json" || { echo "phase A failed at $1: invalid search summary" >&2; FAILED=1; }
}

lag_sampler() {  # <out-file> — max lag sampled every 5 s until the file "<out>.stop" exists
  local f="$1" max=0 cur
  while [[ ! -e "$f.stop" ]]; do
    cur="$(sz_max_lag "$SZ_BASE_URL" "$(token)" "${ENT[@]}" 2>/dev/null || echo 0)"; (( cur > max )) && max=$cur
    echo "$max" > "$f.tmp" && mv "$f.tmp" "$f"; sleep 5     # write-then-rename: a reader never sees an empty file

  done
}

ingest_phase() {  # <tier> <phase B|C> <expected> <with-search 0|1> — the delta file is already staged as live/2-delta.csv
  local tier="$1" ph="$2" expected="$3" with_search="$4" f="$OUT/$1-$2" k6pid="" lagpid="" k6name="lmsizing-k6-$1-$2-$$"; K6NAME=""
  echo 0 > "$f.maxlag"; rm -f "$f.maxlag.stop"
  lag_sampler "$f.maxlag" & lagpid=$!
  if [[ "$with_search" == 1 ]]; then
    # the k6 load must outlive the run: a generous duration, stopped when the run reaches its terminal state
    rm -f "$OUT/$tier-C-search.json"
    K6NAME="$k6name"; k6_run "$((CONV_TIMEOUT + 600))s" C "$tier-C-search.json" "$k6name" & k6pid=$!
    # do not start the ingestion until k6 is really sending: the named container exists AND has logged its first request
    local up=0
    for _ in $(seq 1 60); do
      kill -0 "$k6pid" 2>/dev/null || break                      # k6 died early: no load, no phase
      docker logs "$k6name" 2>&1 | grep -q SZ_FIRST_REQUEST && { up=1; break; }
      sleep 2
    done
    if [[ $up != 1 ]]; then
      echo "phase C failed at $tier: k6 never sent a request" >&2; FAILED=1
      docker kill "$k6name" >/dev/null 2>&1 || true; wait "$k6pid" 2>/dev/null || true
      touch "$f.maxlag.stop"; kill "$lagpid" 2>/dev/null || true; return
    fi
  fi
  local t0=$SECONDS
  local ing; ing="$(bash "$S/fixture.sh" load)"            # prints rows_out=<n> seconds=<s>; returns at terminal state
  local run_s=$((SECONDS - t0)); local t1=$SECONDS
  # Lag during the load: the maximum seen up to the run's terminal state. The ingestion itself takes seconds; the lag
  # that matters appears while the projection drains, so that maximum is recorded separately below.
  local max_load; max_load="$(cat "$f.maxlag")"
  # k6 keeps its rate through the whole catch-up (phase C): the projection's writes are what competes with Search.
  local ok=1; sz_wait_converged "$CONV_TIMEOUT" "$SZ_BASE_URL" "$(token)" "$expected" "${ENT[@]}" || ok=0
  local conv_s=$((SECONDS - t1))
  touch "$f.maxlag.stop"; kill "$lagpid" 2>/dev/null || true; wait "$lagpid" 2>/dev/null || true
  local max_catchup; max_catchup="$(cat "$f.maxlag")"
  # stop k6 gracefully (SIGINT makes it still write handleSummary) — exactly the container this phase named, never another k6
  if [[ -n "$k6pid" ]]; then
    docker kill --signal=INT "$k6name" >/dev/null 2>&1 || true
    wait "$k6pid" 2>/dev/null || true                            # k6's exit status after SIGINT is not the verdict
    check_search_summary "$OUT/$tier-C-search.json" || { echo "phase C failed at $tier: invalid search summary" >&2; FAILED=1; }
    K6NAME=""
  fi
  printf '{"tier":%s,"phase":"%s","ingest":"%s","run_seconds":%s,"catchup_seconds":%s,"max_lag_during_load":%s,"max_lag_catchup":%s,"converged":%s,"disk":%s}\n' \
    "$tier" "$ph" "$ing" "$run_s" "$conv_s" "$max_load" "$max_catchup" "$([[ $ok == 1 ]] && echo true || echo false)" "$(record_disk)" > "$f-ingest.json"
  [[ $ok == 1 ]] || { echo "phase $ph failed at $tier: no convergence" >&2; FAILED=1; }
}

for N in ${TIERS//,/ }; do
  # The projection catches up at one 500-document batch per index per one-minute tick (measured, run 1): a tier's
  # convergence cap is twice that time plus 10 minutes, never a fixed 30.
  CONV_TIMEOUT=$(( (N / 500 + 1) * 120 + 600 ))
  DELTA=$((N / 10)); BASE_EXPECTED=$((N + SEED_ROWS)); AFTER=$((N + DELTA + SEED_ROWS))
  [[ "$(free_mb)" -ge "$MIN_FREE_MB" ]] || { echo "free space $(free_mb) MB < floor $MIN_FREE_MB MB before tier $N — aborting" >&2; exit 1; }
  echo "=== tier $N rows per entity (delta $DELTA) ==="
  bash "$S/snapshot.sh" restore seed                # exits non-zero unless every entity is back to the seed row / seed document
  printf '%s' "$(record_disk)" > "$OUT/$N-disk-before.json"
  rm -f "$LIVE"/*.csv
  python3 "$S/gen_corpus.py" csv --rows "$N" --range base --out "$LIVE/1-base.csv"   # the cursor reads 1-base.csv, then 2-delta.csv
  bash "$S/fixture.sh" load >/dev/null
  sz_wait_converged "$CONV_TIMEOUT" "$SZ_BASE_URL" "$(token)" "$BASE_EXPECTED" "${ENT[@]}" || { echo "base load did not converge at $N" >&2; FAILED=1; continue; }
  bash "$S/snapshot.sh" take "base-$N"
  phase_A "$N"
  python3 "$S/gen_corpus.py" csv --rows "$DELTA" --range 1 --out "$LIVE/2-delta.csv"
  ingest_phase "$N" B "$AFTER" 0
  bash "$S/snapshot.sh" restore "base-$N"          # B and C start from the same state at N (exits non-zero on mismatch)
  python3 "$S/gen_corpus.py" csv --rows "$DELTA" --range 2 --out "$LIVE/2-delta.csv"   # equivalent, key-disjoint from range 1
  ingest_phase "$N" C "$AFTER" 1
  printf '%s' "$(record_disk)" > "$OUT/$N-disk-after.json"
done
BUDGET_P95_MS="$BUDGET_P95_MS" FAILED="$FAILED" python3 - "$OUT" <<'PYEOF' > "$OUT/summary.md"
import glob, json, os, re, sys
d = sys.argv[1]
def load(path):
    try: return json.load(open(path))
    except (OSError, ValueError): return None
if os.environ.get("FAILED") == "1":
    print("**NOT PUBLISHABLE — at least one phase failed validation or did not converge; see the orchestrator's output. The rows below are for diagnosis only.**\n")
print("| tier | phase | exact p95 ms | text p95 ms | fuzzy p95 ms | prefix p95 ms | min hit share | ingest rows/s | run s | catch-up s | max lag load / catch-up s | converged |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|")
tiers = sorted({int(os.path.basename(p).split('-')[0]) for p in glob.glob(d + '/*-*-*.json') if re.match(r'\d+-', os.path.basename(p))})
for t in tiers:
    for ph in "ABC":
        s = load(f"{d}/{t}-{ph}-search.json"); i = load(f"{d}/{t}-{ph}-ingest.json")
        if s is None and i is None: continue
        budget = float(os.environ.get("BUDGET_P95_MS", "500"))
        p95 = lambda m: (f"{s['modes'][m]['p95']:.0f}" + ("*" if s['modes'][m]['p95'] > budget else "")) if s and s['modes'].get(m) else "-"
        rows = run = None
        if i:
            m = re.search(r"rows_out=(\d+) seconds=([\d.]+)", i["ingest"]); rows = int(m.group(1)) if m else None; run = float(m.group(2)) if m else None
        rps = f"{rows / run:.0f}" if rows and run else "-"
        hit = f"{min([v['hit_share'] for v in s['modes'].values() if v and v.get('hit_share') is not None] or [0]):.2f}" if s else "-"
        secs = f"{run:.2f}" if i and run else "-"
        print(f"| {t} | {ph} | {p95('exact')} | {p95('text')} | {p95('fuzzy')} | {p95('prefix')} | "
              f"{hit} | {rps} | {secs} | {i['catchup_seconds'] if i else '-'} | "
              f"{i['max_lag_during_load'] if i else '-'} / {i['max_lag_catchup'] if i else '-'} | {i['converged'] if i else '-'} |")
print(f"\n`*` = p95 above the {budget:.0f} ms budget (an observation, not a failed run).")
PYEOF
[[ $FAILED -eq 0 ]] || { echo "SOME PHASE FAILED — results are not publishable" >&2; exit 1; }
echo "results: $OUT"
