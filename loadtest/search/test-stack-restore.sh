#!/usr/bin/env bash
# loadtest/search/test-stack-restore.sh — stack.sh's pg_restore wrapper against fake `pg_restore` outcomes.
# pg_restore --clean exits 1 on one known, harmless error (core.audit_event_default_pkey, a partition's inherited key).
# That error alone is tolerated; ANY other failure — a different error, or a failure with no pg_restore error line at
# all (container down, docker error) — must fail the restore, or a snapshot "restores" into a corrupt database.
set -uo pipefail
cd "$(dirname "$0")"
eval "$(sed -n '/^cmd_pgrestore() {/,/^}/p' stack.sh)"
ok=0; fail=0
BENIGN='pg_restore: error: could not execute query: ERROR:  cannot drop inherited constraint "audit_event_default_pkey" of relation "audit_event_default"
Command was: ALTER TABLE IF EXISTS ONLY core.audit_event_default DROP CONSTRAINT IF EXISTS audit_event_default_pkey;
pg_restore: warning: errors ignored on restore: 1'
case_() {  # <name> <stderr text> <exit code> <expected: ok|fail>
  local name="$1" text="$2" rc="$3" want="$4" got
  pg() { printf '%s\n' "$FAKE_ERR" >&2; return "$FAKE_RC"; }
  if FAKE_ERR="$text" FAKE_RC="$rc" cmd_pgrestore /dev/null 2>/dev/null; then got=ok; else got=fail; fi
  if [[ "$got" == "$want" ]]; then ok=$((ok+1)); else echo "FAIL ($name): wanted $want, got $got"; fail=$((fail+1)); fi
}
case_ "success"                         ""                                                        0 ok
case_ "only the known benign error"     "$BENIGN"                                                 1 ok
case_ "benign plus a real error"        "$BENIGN
pg_restore: error: could not execute query: ERROR:  permission denied for schema core"           1 fail
case_ "a different error alone"         "pg_restore: error: connection to server failed"          1 fail
case_ "docker failure, no pg_restore line" "service \"postgres\" is not running"                  1 fail
case_ "non-zero exit, no output at all" ""                                                        1 fail
echo "restore tests: $ok ok, $fail failed"; [ "$fail" -eq 0 ]
