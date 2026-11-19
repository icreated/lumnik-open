#!/usr/bin/env bash
# #1998: a selfhost backup is owner-only whatever the caller's umask. Runs the real deploy/selfhost/backup.sh in a
# scratch copy of the tree, against a fake `docker` (no stack touched), under umask 022, over a snapshot left
# world-readable by an older run, and checks every mode.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/tree/deploy" "$WORK/bin" "$WORK/tree/backups/20200101T000000Z"
cp -R "$ROOT/deploy/selfhost" "$WORK/tree/deploy/"
( umask 022; printf 'old\n' > "$WORK/tree/backups/20200101T000000Z/lumnik.dump" )
# The fake docker: `compose exec` prints a dump (the hub's database, then Keycloak's); the rest succeeds silently.
cat > "$WORK/bin/docker" <<'SHIM'
#!/usr/bin/env bash
case " $* " in
  *" exec "*) printf 'PGDMP fake dump\n' ;;
esac
SHIM
chmod +x "$WORK/bin/docker"

( cd "$WORK/tree" && umask 022 && PATH="$WORK/bin:$PATH" bash deploy/selfhost/backup.sh >/dev/null )

mode() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }   # GNU first: its -f means file system
fail=0
expect() {
  local m; m="$(mode "$2")"
  if [[ "$m" == "$1" ]]; then echo "ok   ${2#"$WORK/tree/"} is $m"
  else echo "FAIL ${2#"$WORK/tree/"} is $m, not $1: a backup must be owner-only"; fail=1; fi
}
expect 700 "$WORK/tree/backups"
for dir in "$WORK/tree/backups/"*; do expect 700 "$dir"; done
expect 600 "$WORK/tree/backups/20200101T000000Z/lumnik.dump"
set -- "$WORK/tree/backups/"2*
new="${!#}"
expect 600 "$new/lumnik.dump"
expect 600 "$new/keycloak.dump"
exit "$fail"
