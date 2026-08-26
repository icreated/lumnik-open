#!/usr/bin/env bash
# Task 9's executable specification: neither infra/keycloak/*.json ships a static
# password anymore (check-realm-no-static-passwords.sh), so every realm user the
# docs instruct a human to log in with an interactive password MUST get a
# per-install one from deploy/selfhost/up.sh — otherwise the documented flow is
# broken the moment the realm ships credential-less. Only deploy/selfhost/up.sh is
# checked here; deploy/helm/up.sh provisions per-cluster secrets through a
# different (ephemeral kind-cluster) path, not a persisted .env.
#
# Docs are the source of truth for "which users a human logs in as": scan for the
# "sign in as <user>" and "demo user: <user>" phrasings and require a matching
# "<USER>_PASSWORD" key provisioned in up.sh.
#
# Scans every living doc (docs/**/*.md + README.md) rather than a hardcoded file list, so a
# new doc that tells a human to log in cannot silently fall outside this gate — that hole is
# exactly how README.md:28 went unchecked. docs/studies/*, docs/snapshot/* and
# docs/superpowers/* are pruned: they are the archival/working trees (see
# extract-open-edition.sh's own "dev story" exclusion), not living instructions — a dated
# session carnet describing what a past run actually did is not a live provisioning contract.
#
# Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

echo "=== check-selfhost-password-provisioning ==="

doc_files=()
while IFS= read -r -d '' f; do doc_files+=("$f"); done < <(
    find docs -name '*.md' \
        -not -path 'docs/studies/*' -not -path 'docs/snapshot/*' -not -path 'docs/superpowers/*' \
        -print0
)
doc_files+=("README.md")

# || [ $? -eq 1 ]: zero doc matches must reach the explanatory failure below, not
# die silently at the grep (set -euo pipefail) — but a real error (rc 2, e.g. an
# unreadable doc file) still fails fast instead of reading as "regex regressed".
users="$(grep -rhoE '(sign in as|demo user:) [A-Za-z0-9_-]+' "${doc_files[@]}" \
  | awk '{print $NF}' | sort -u)" || [ $? -eq 1 ]

if [ -z "$users" ]; then
    echo "❌ no 'sign in as <user>' or 'demo user: <user>' instruction found — this check's regex regressed or the docs changed shape"
    exit 1
fi

for user in $users; do
    key="$(echo "$user" | tr '[:lower:]' '[:upper:]')_PASSWORD"

    if ! grep -q "$key" deploy/selfhost/up.sh; then
        echo "❌ docs instruct logging in as '$user' but deploy/selfhost/up.sh never mentions $key"
        fail=1
        continue
    fi

    if ! grep -q "\"$user" deploy/selfhost/up.sh && ! grep -q "'$user" deploy/selfhost/up.sh; then
        echo "❌ docs instruct logging in as '$user' but deploy/selfhost/up.sh never names that username for provisioning"
        fail=1
        continue
    fi

    echo "✓ $user: $key provisioned in deploy/selfhost/up.sh"
done

if [ $fail -ne 0 ]; then
    echo ""
    echo "Selfhost password-provisioning check FAILED."
    echo "Every documented login user needs a per-install password minted by up.sh — see set_realm_user_password() for the pattern."
    exit 1
fi

echo ""
echo "Selfhost password-provisioning check PASSED."
