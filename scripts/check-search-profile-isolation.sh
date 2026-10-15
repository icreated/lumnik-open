#!/usr/bin/env bash
# Proves no Search provider coordinate is resolvable on the classpath without -Psearch.
# Fail-closed: an empty or malformed dependency:tree capture is treated as a failure, never
# as "nothing found, must be clean" (see docs/superpowers/specs/2026-09-27-search-opt-in-packaging-design.md).
set -euo pipefail
cd "$(dirname "$0")/.."

# [:.] after the groupId root, not just `:` — real client artifacts use subgroup ids
# (org.opensearch.client:opensearch-java, co.elastic.clients:elasticsearch-java), which a
# colon-only anchor misses entirely (Copilot review, PR #717).
FORBIDDEN_PATTERN='org\.opensearch[:.]|co\.elastic[:.]|io\.lumnik:lumnik-search-'
ROOT_MARKER='io\.lumnik:lumnik-hub'

# Testable in isolation from mvn: given a captured tree file, assert it is non-empty, contains
# the reactor's own root artifact, and carries none of the forbidden coordinates. -q on
# `dependency:tree` suppresses the very [INFO] lines this greps, so callers must NEVER use -q —
# an empty/broken capture must read as a failure here, never as "nothing found, must be clean".
assert_tree_clean() {
    local tree_file="$1" label="$2"
    if [ ! -s "$tree_file" ]; then
        echo "❌ [$label] dependency:tree capture is empty or missing — refusing to treat that as clean"
        return 1
    fi
    if ! grep -qE "$ROOT_MARKER" "$tree_file"; then
        echo "❌ [$label] dependency:tree capture does not even contain $ROOT_MARKER — capture is broken, not clean"
        return 1
    fi
    local leaks
    leaks=$(grep -E "$FORBIDDEN_PATTERN" "$tree_file" || true)
    if [ -n "$leaks" ]; then
        echo "❌ [$label] Search provider coordinates leaked onto the classpath without -Psearch:"
        echo "$leaks" | sed 's/^/    /'
        return 1
    fi
    echo "✓ [$label] no Search provider coordinates on the classpath"
    return 0
}

run_selftest() {
    local tmp ok=1
    tmp=$(mktemp -d)
    trap "rm -rf '$tmp'" EXIT

    : > "$tmp/empty.txt"
    if assert_tree_clean "$tmp/empty.txt" selftest >/dev/null 2>&1; then
        echo "❌ selftest: an empty capture was wrongly treated as clean"; ok=0
    else
        echo "✓ selftest: an empty capture fails closed"
    fi

    printf 'io.lumnik:lumnik-common:jar:1.0:compile\n' > "$tmp/no-root.txt"
    if assert_tree_clean "$tmp/no-root.txt" selftest >/dev/null 2>&1; then
        echo "❌ selftest: a capture missing the reactor root was wrongly treated as clean"; ok=0
    else
        echo "✓ selftest: a capture missing the reactor root fails closed"
    fi

    printf 'io.lumnik:lumnik-hub:jar:1.0:compile\norg.opensearch:opensearch-rest-client:jar:2.0:compile\n' > "$tmp/leaked.txt"
    if assert_tree_clean "$tmp/leaked.txt" selftest >/dev/null 2>&1; then
        echo "❌ selftest: a leaked coordinate was wrongly treated as clean"; ok=0
    else
        echo "✓ selftest: a leaked coordinate fails"
    fi

    # Real client artifacts ship under a subgroup id, not the bare groupId root — pin both,
    # since a colon-only anchor on org.opensearch/co.elastic silently misses these (PR #717).
    printf 'io.lumnik:lumnik-hub:jar:1.0:compile\norg.opensearch.client:opensearch-java:jar:2.0:compile\n' > "$tmp/leaked-opensearch-client.txt"
    if assert_tree_clean "$tmp/leaked-opensearch-client.txt" selftest >/dev/null 2>&1; then
        echo "❌ selftest: org.opensearch.client:* subgroup coordinate was wrongly treated as clean"; ok=0
    else
        echo "✓ selftest: org.opensearch.client:* subgroup coordinate fails"
    fi

    printf 'io.lumnik:lumnik-hub:jar:1.0:compile\nco.elastic.clients:elasticsearch-java:jar:8.0:compile\n' > "$tmp/leaked-elastic-clients.txt"
    if assert_tree_clean "$tmp/leaked-elastic-clients.txt" selftest >/dev/null 2>&1; then
        echo "❌ selftest: co.elastic.clients:* subgroup coordinate was wrongly treated as clean"; ok=0
    else
        echo "✓ selftest: co.elastic.clients:* subgroup coordinate fails"
    fi

    printf 'io.lumnik:lumnik-hub:jar:1.0:compile\nio.lumnik:lumnik-search-opensearch:jar:1.0:runtime\n' > "$tmp/leaked-cluster.txt"
    if assert_tree_clean "$tmp/leaked-cluster.txt" selftest >/dev/null 2>&1; then
        echo "❌ selftest: the cluster provider's own coordinate was wrongly treated as clean"; ok=0
    else
        echo "✓ selftest: the cluster provider's own coordinate fails"
    fi

    printf 'io.lumnik:lumnik-hub:jar:1.0:compile\nio.lumnik:lumnik-common:jar:1.0:compile\n' > "$tmp/clean.txt"
    if assert_tree_clean "$tmp/clean.txt" selftest >/dev/null 2>&1; then
        echo "✓ selftest: a genuinely clean tree passes"
    else
        echo "❌ selftest: a genuinely clean tree was wrongly flagged"; ok=0
    fi

    echo ""
    if [ "$ok" -eq 1 ]; then
        echo "check-search-profile-isolation --selftest PASSED"
        return 0
    fi
    echo "check-search-profile-isolation --selftest FAILED"
    return 1
}

if [ "${1:-}" = "--selftest" ]; then
    run_selftest
    exit $?
fi

echo "=== check-search-profile-isolation ==="
fail=0
for edition_label in default openEdition; do
    tree_file=$(mktemp)
    if [ "$edition_label" = "default" ]; then
        mvn -B -ntp dependency:tree -pl lumnik-hub -am > "$tree_file" 2>&1 || {
            echo "❌ [$edition_label] mvn dependency:tree failed:"
            tail -50 "$tree_file" | sed 's/^/    /'
            rm -f "$tree_file"
            fail=1
            continue
        }
    else
        mvn -B -ntp dependency:tree -pl lumnik-hub -am -DopenEdition > "$tree_file" 2>&1 || {
            echo "❌ [$edition_label] mvn dependency:tree failed:"
            tail -50 "$tree_file" | sed 's/^/    /'
            rm -f "$tree_file"
            fail=1
            continue
        }
    fi
    assert_tree_clean "$tree_file" "$edition_label" || fail=1
    rm -f "$tree_file"
done

if [ $fail -ne 0 ]; then
    echo ""
    echo "check-search-profile-isolation FAILED."
    exit 1
fi

echo ""
echo "check-search-profile-isolation PASSED."
