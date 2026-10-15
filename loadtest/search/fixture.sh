#!/usr/bin/env bash
# Declares the four equivalent entities and loads CSVs through the REAL csv-file connector (#750).
# One CSV feeds four connectors (sz_exact, sz_text, sz_fuzzy, sz_prefix): same rows, four tables, four SearchIndexes
# differing ONLY in the declared match mode of `name`, so a per-mode p95 is attributable.
# The csv-file connector keeps a cursor {file, rowsProcessed} over the LEXICALLY SORTED glob result and never reads a
# file that sorts before it, so a re-pointed single path reads nothing once a multi-chunk file has been ingested (run 1
# found 0 rows). The fixture therefore reads a glob over the live/ directory, and the caller stages files there with
# names that sort in ingestion order (0-seed.csv, 1-base.csv, 2-delta.csv).
#   fixture.sh init   connectors -> runs (tables appear) -> entities -> search indexes  (live/ holds 0-seed.csv)
#   fixture.sh load   run the four connectors over whatever live/ holds; prints rows_out=<n> seconds=<s>
#                     (seconds = earliest run start to latest run end, from the run reports; rows_out = rows ADDED to the four tables, not the run report's records_out: the cursor re-reads
#                     the tail of the last chunked file, and the table deduplicates it)
set -euo pipefail
cd "$(dirname "$0")"; source ./lib.sh; source .run/env
MODES=(exact text fuzzy prefix)
TOKEN="$(bash ./stack.sh token)"
RUN_TIMEOUT="${RUN_TIMEOUT:-1800}"

connector_yaml() {  # <mode>
  cat <<YAML
apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata: {name: sz_$1, connector_type: csv-file}
spec:
  transport: {kind: local, path: "/sizing-data/live/*.csv"}
  parser: {has_header: true}
  after_process: none
YAML
}
entity_yaml() {  # <mode>
  cat <<YAML
kind: Entity
name: sz_$1
match: [code]
columns:
  code: {label: Code}
  name: {label: Name}
  city: {label: City}
sources:
  - table: t_sz_$1
    map: {code: code, name: name, city: city}
YAML
}
index_yaml() {  # <mode> — code and city ride along (return-only); only `name` is searched, in the declared mode
  cat <<YAML
apiVersion: search.lumnik.io/v1
kind: SearchIndex
metadata: {name: sz_$1}
spec:
  entity: sz_$1
  language: simple
  fields:
    - {name: code}
    - {name: name, match: $1}
    - {name: city}
YAML
}

post() {  # <path> — reads the YAML on stdin, prints the JSON answer; any non-2xx fails loudly
  curl -fsS --max-time 60 -X POST "$SZ_BASE_URL$1" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/yaml" --data-binary @-
}
field() { python3 -c 'import sys,json;print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

table_rows() {  # sum of the four tables' row counts (0 for a table that does not exist yet)
  local m n total=0
  for m in "${MODES[@]}"; do
    if [[ "$(bash ./stack.sh psql -tAc "select to_regclass('connector.t_sz_$m') is not null")" == t ]]; then
      n="$(bash ./stack.sh psql -tAc "select count(*) from connector.t_sz_$m")"
      total=$((total + n))
    fi
  done
  echo "$total"
}

run_connectors() {  # apply + trigger the four connectors, wait for their terminal states
  local m id rid ids=() bodies=() st before body deadline=$((SECONDS + RUN_TIMEOUT))
  before="$(table_rows)"
  for m in "${MODES[@]}"; do
    id="$(connector_yaml "$m" | post /api/platform/connectors/apply | field connectorId)"
    rid="$(curl -fsS -X POST "$SZ_BASE_URL/api/platform/connectors/$id/run" -H "Authorization: Bearer $TOKEN" | field runId)"
    ids+=("$rid")
  done
  for rid in "${ids[@]}"; do
    while :; do
      body="$(curl -fsS "$SZ_BASE_URL/api/platform/runs/$rid" -H "Authorization: Bearer $TOKEN")"
      st="$(echo "$body" | field status)"
      case "$st" in
        Completed) bodies+=("$body"); break ;;
        Partial|Failed|Cancelled) echo "run $rid ended $st: $body" >&2; return 1 ;;
      esac
      (( SECONDS < deadline )) || { echo "run $rid still $st after ${RUN_TIMEOUT}s" >&2; return 1; }
      sleep 0.25
    done
  done
  # The duration comes from the hub's own run reports (earliest start to latest end), not from a timer around the
  # apply/trigger/poll calls: those are control plane, and under a search load they compete with it.
  local joined; joined="$(IFS=,; echo "${bodies[*]}")"
  echo "rows_out=$(( $(table_rows) - before )) seconds=$(echo "[$joined]" | python3 ./run_window.py)"
}

case "${1:-}" in
  init)
    run_connectors >/dev/null                                   # the tables exist only after a first run
    for m in "${MODES[@]}"; do
      [[ "$(bash ./stack.sh psql -tAc "select to_regclass('connector.t_sz_$m') is not null")" == t ]] \
        || { echo "table connector.t_sz_$m missing after the first run" >&2; exit 1; }
    done
    for m in "${MODES[@]}"; do entity_yaml "$m" | post /api/entities/apply >/dev/null; done
    for m in "${MODES[@]}"; do index_yaml "$m" | post /api/search-indexes/apply >/dev/null; done
    echo "fixture initialised" ;;
  load) run_connectors ;;
  *) echo "usage: fixture.sh init | load" >&2; exit 2 ;;
esac
