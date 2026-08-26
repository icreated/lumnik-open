#!/usr/bin/env bash
# Guards against a shipped Keycloak realm carrying a live, non-temporary
# password. infra/keycloak/*.json ships on the open-edition extraction
# allowlist (scripts/extract-open-edition.sh) and is also the seed data for
# deploy/selfhost/up.sh, so a static "password": "…", "temporary": false on
# any user becomes a world-readable working credential at the flip.
#
# Fails if ANY user in infra/keycloak/*.json has a "credentials" entry of
# type "password" whose "temporary" is false OR absent (absent counts as
# not temporary — Keycloak itself treats a missing field as false). This is
# intentionally not scoped to the "integrator" username: the point is to
# catch the next user who ships the same way, not just today's offender.
#
# Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

command -v jq >/dev/null 2>&1 || {
    echo "❌ 'jq' is required by this script and is not on PATH"
    exit 1
}

fail=0

echo "=== check-realm-no-static-passwords ==="

for realm in infra/keycloak/*.json; do
    # Explicit `if` around the assignment: under `set -e`, a failing `jq` inside
    # `var=$(...)` would otherwise abort the whole script mid-loop with jq's bare,
    # unattributed parse error — no filename, no ❌, no FAILED banner. Capturing
    # stderr and checking the exit status here keeps a malformed realm file a
    # clean, attributed gate failure instead of an unrelated-looking crash.
    err=$(mktemp)
    if ! offenders=$(jq -r '
        (.users // [])[]
        | . as $u
        | ($u.credentials // [])[]
        | select(.type == "password" and ((.temporary // false) == false))
        | $u.username
    ' "$realm" 2>"$err"); then
        echo "❌ $realm is not valid JSON (jq failed to parse it):"
        sed 's/^/    /' "$err"
        rm -f "$err"
        fail=1
        continue
    fi
    rm -f "$err"

    if [ -n "$offenders" ]; then
        echo "❌ $realm ships a non-temporary password for:"
        echo "$offenders" | sed 's/^/    /'
        fail=1
    else
        echo "✓ $realm has no non-temporary password credentials"
    fi
done

if [ $fail -ne 0 ]; then
    echo ""
    echo "Realm static-password check FAILED."
    echo "Remove the offending 'credentials' block(s) — the user and its roles may stay."
    exit 1
fi

echo ""
echo "Realm static-password check PASSED."
