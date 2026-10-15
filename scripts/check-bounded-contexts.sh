#!/usr/bin/env bash
# Verifies the substrate↔extension boundary established by extraction-spi-v1.
# Run from the repo root.

set -e
cd "$(dirname "$0")/.."

fail=0

# Every file scan goes through this. `grep -rl … 2>/dev/null || true` reads a SEARCH FAILURE —
# a renamed directory, an unreadable file, a pattern a future grep rejects — as "no violations",
# which is the one answer this script must never give by accident. grep exits 0 on match,
# 1 on no-match, >=2 on error; only the first two are answers.
scan() {
    local pattern="$1"; shift
    local err out rc
    err=$(mktemp)
    # The `if` makes grep's failure explicitly examined, so errexit cannot fire on rc=1 (no
    # matches — the normal, healthy answer). Not decoration: `out=$(grep …); rc=$?` aborts under
    # set -e at top level but NOT inside a function called from a command substitution (measured,
    # bash 3.2). Relying on that difference across two bash versions — 3.2 on a dev mac, 5.x on
    # the runner — is not something a boundary guard should do.
    if out=$(grep -rlE "$pattern" "$@" 2>"$err"); then rc=0; else rc=$?; fi
    if [ "$rc" -ge 2 ]; then
        echo "❌ boundary scan FAILED (grep exit $rc) over: $*" >&2
        sed 's/^/    /' "$err" >&2
        rm -f "$err"
        exit 1
    fi
    rm -f "$err"
    [ "$rc" -eq 0 ] && printf '%s\n' "$out"
    return 0
}

# Closed-implementation Search provider code (#702): io.lumnik.search.* is the reserved
# namespace for #705/#672's own packages (io.lumnik.search.pg, io.lumnik.search.cluster, …).
# The public contract, io.lumnik.extraction.spi.search.IndexedSearchPort, deliberately lives
# outside this prefix and must never be caught here. Boundary is (\.|;): catches a class
# reference (io.lumnik.search.Foo) and a root package declaration (package io.lumnik.search;)
# without a false positive on io.lumnik.searchable.
readonly SEARCH_PROVIDER_PATTERN='io\.lumnik\.search(\.|;)|org\.opensearch\.|co\.elastic\.'

# Check 1b as a function: TEST code may couple to the closed side, but only under a location
# or name that says so (see the inline comment at the call site below for the full rule).
check_1b() {
    local test_dir="$1"
    local closed_coupled mistests
    # `|| exit 1`, not left to set -e: this function is called as `check_1b … || fail=1` at the
    # call site, and errexit is suspended for everything a function does while it is itself the
    # left side of `||`. Without this, a scan() failure (rc>=2, e.g. a renamed/missing directory)
    # would leave $closed_coupled empty and this check would print ✓ on a search that never ran —
    # exactly the false "no violations" scan()'s own header comment says must never happen.
    closed_coupled=$(scan "io\.lumnik\.rag\.|dev\.langchain4j\.|io\.lumnik\.llm\.langchain\.|io\.lumnik\.hub\.pro\.|io\.lumnik\.identity\.scim\.|${SEARCH_PROVIDER_PATTERN}" \
        "$test_dir") || exit 1
    mistests=$(printf '%s' "$closed_coupled" | grep -Ev 'ClosedIT\.java$|/hub/pro/' || true)
    if [ -n "$mistests" ]; then
        echo "❌ Test sources import closed-edition types but are not named *ClosedIT.java:"
        echo "$mistests" | sed 's/^/    /'
        echo "    Rename them to *ClosedIT.java (they travel with the closed side), or drop the import."
        return 1
    fi
    echo "✓ Closed-coupled test sources are all named *ClosedIT (excluded from the open edition)"
    return 0
}

# Check 1c as a function: MAIN code may live on the closed side, but only under a location
# that says so (see the inline comment at the call site below for the full rule).
check_1c() {
    local main_dir="$1" identity_dir="$2"
    local pro_refs open_pro_refs
    # `|| exit 1` — same reasoning as check_1b above: errexit is suspended inside a function
    # called as the left side of `||`, so a scan() failure here must be caught explicitly.
    pro_refs=$(scan "io\.lumnik\.hub\.pro\.|io\.lumnik\.identity\.scim\.|${SEARCH_PROVIDER_PATTERN}" \
        "$main_dir" "$identity_dir") || exit 1
    open_pro_refs=$(printf '%s' "$pro_refs" | grep -Ev '/hub/pro/|/identity/scim/' || true)
    if [ -n "$open_pro_refs" ]; then
        echo "❌ Open main sources reference pro-only code (io.lumnik.hub.pro / io.lumnik.identity.scim):"
        echo "$open_pro_refs" | sed 's/^/    /'
        echo "    Route it through the ExternalIdentityResolver seam, or move the file under the pro tree."
        return 1
    fi
    echo "✓ Pro-only main code is referenced only from within the pro trees (open edition excludes them)"
    return 0
}

# Check 1d as a function (#857): hub MAIN code reaches a JDBC dialect provider only through the SPI —
# exactly io.lumnik.jdbc.spi, never io.lumnik.jdbc.postgresql (or any other provider package). Two
# readable stages instead of one clever pattern: list every io.lumnik.jdbc.<segment> that occurs,
# then drop the exact SPI segment; whatever is left is a provider leaking into the hub. Hub TEST
# code is not scanned: it legitimately constructs the embedded default provider.
check_1d() {
    local main_dir="$1" err found rc leaked
    err=$(mktemp)
    # Same grep exit-code discipline as scan(): 0 = match, 1 = none (healthy), >=2 = error.
    if found=$(grep -rhoE 'io\.lumnik\.jdbc\.[A-Za-z0-9_]+' "$main_dir" 2>"$err"); then rc=0; else rc=$?; fi
    if [ "$rc" -ge 2 ]; then
        echo "❌ boundary scan FAILED (grep exit $rc) over: $main_dir" >&2
        sed 's/^/    /' "$err" >&2
        rm -f "$err"
        exit 1
    fi
    rm -f "$err"
    leaked=$(printf '%s\n' "$found" | sort -u | grep -vx 'io\.lumnik\.jdbc\.spi' | grep -v '^$' || true)
    if [ -n "$leaked" ]; then
        echo "❌ Hub main code references a JDBC provider package (only io.lumnik.jdbc.spi is allowed):"
        echo "$leaked" | sed 's/^/    /'
        echo "    Reach providers through JdbcDialectRegistry / Instance<JdbcDialect>, never by class."
        return 1
    fi
    echo "✓ Hub main code reaches JDBC providers only through io.lumnik.jdbc.spi"
    return 0
}

# --selftest (on demand, not a CI gate — mirrors check-fqn-hygiene.py --selftest): proves the
# regex boundary catches a leak without needing a real Search provider module to exist yet.
run_selftest() {
    local tmp ok=1
    tmp=$(mktemp -d)
    trap "rm -rf '$tmp'" EXIT
    mkdir -p "$tmp/empty-identity"

    mkdir -p "$tmp/main-leak/src/main/java/io/lumnik/hub/search"
    cat > "$tmp/main-leak/src/main/java/io/lumnik/hub/search/LeakyGate.java" <<'EOF'
package io.lumnik.hub.search;
import io.lumnik.search.pg.Fake;
class LeakyGate {}
EOF
    if check_1c "$tmp/main-leak/src/main/java" "$tmp/empty-identity" >/dev/null 2>&1; then
        echo "❌ selftest: check_1c did not fail on a main-tree leak"; ok=0
    else
        echo "✓ selftest: check_1c fails on a main-tree leak"
    fi

    mkdir -p "$tmp/test-leak/src/test/java/io/lumnik/hub/search"
    cat > "$tmp/test-leak/src/test/java/io/lumnik/hub/search/LeakyGateTest.java" <<'EOF'
package io.lumnik.hub.search;
import io.lumnik.search.pg.Fake;
class LeakyGateTest {}
EOF
    if check_1b "$tmp/test-leak/src/test/java" >/dev/null 2>&1; then
        echo "❌ selftest: check_1b did not fail on a non-ClosedIT test-tree leak"; ok=0
    else
        echo "✓ selftest: check_1b fails on a non-ClosedIT test-tree leak"
    fi

    # A clean tree, including a legitimate io.lumnik.hub.search.* reference — textually close
    # to io.lumnik.search.* but a different, allowed package — must pass both checks.
    mkdir -p "$tmp/clean/src/main/java/io/lumnik/hub/search"
    cat > "$tmp/clean/src/main/java/io/lumnik/hub/search/SearchGate.java" <<'EOF'
package io.lumnik.hub.search;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
class SearchGate {}
EOF
    mkdir -p "$tmp/clean/src/test/java/io/lumnik/hub/search"
    cat > "$tmp/clean/src/test/java/io/lumnik/hub/search/SearchGateTest.java" <<'EOF'
package io.lumnik.hub.search;
class SearchGateTest {}
EOF
    if check_1c "$tmp/clean/src/main/java" "$tmp/empty-identity" >/dev/null 2>&1 \
        && check_1b "$tmp/clean/src/test/java" >/dev/null 2>&1; then
        echo "✓ selftest: a clean tree (including a legitimate io.lumnik.hub.search.* reference) passes"
    else
        echo "❌ selftest: a clean tree was wrongly flagged"; ok=0
    fi

    # A scan() error (e.g. a renamed/missing directory) must fail closed, never print a false ✓.
    # Regression case: errexit is suspended inside a function called as the left side of `||`,
    # so check_1b/check_1c must `exit 1` explicitly on a scan() failure rather than let an empty
    # result silently read as "no violations". Run in a subshell — the fixed functions really do
    # `exit`, which would otherwise end this selftest too.
    if ( check_1b "$tmp/does-not-exist/src/test/java" ) >/dev/null 2>&1; then
        echo "❌ selftest: check_1b did not fail closed on a scan error (missing directory)"; ok=0
    else
        echo "✓ selftest: check_1b fails closed on a scan error (missing directory)"
    fi
    if ( check_1c "$tmp/does-not-exist/src/main/java" "$tmp/empty-identity" ) >/dev/null 2>&1; then
        echo "❌ selftest: check_1c did not fail closed on a scan error (missing directory)"; ok=0
    else
        echo "✓ selftest: check_1c fails closed on a scan error (missing directory)"
    fi

    # JDBC providers (#857): hub MAIN code may reference exactly io.lumnik.jdbc.spi. `spark` is the
    # case a hand-rolled "not s…" pattern lets through (a package that starts with the SPI's letters).
    for leaky in postgresql spark oracle; do
        mkdir -p "$tmp/jdbc-$leaky/src/main/java/io/lumnik/hub/connector/jdbc"
        cat > "$tmp/jdbc-$leaky/src/main/java/io/lumnik/hub/connector/jdbc/Leaky.java" <<EOF
package io.lumnik.hub.connector.jdbc;
import io.lumnik.jdbc.$leaky.SomeDialect;
class Leaky {}
EOF
        if check_1d "$tmp/jdbc-$leaky/src/main/java" >/dev/null 2>&1; then
            echo "❌ selftest: check_1d did not fail on io.lumnik.jdbc.$leaky"; ok=0
        else
            echo "✓ selftest: check_1d fails on io.lumnik.jdbc.$leaky"
        fi
    done
    mkdir -p "$tmp/jdbc-spi/src/main/java/io/lumnik/hub/connector/jdbc"
    cat > "$tmp/jdbc-spi/src/main/java/io/lumnik/hub/connector/jdbc/Clean.java" <<'EOF'
package io.lumnik.hub.connector.jdbc;
import io.lumnik.jdbc.spi.JdbcDialect;
class Clean {}
EOF
    if check_1d "$tmp/jdbc-spi/src/main/java" >/dev/null 2>&1; then
        echo "✓ selftest: check_1d passes on io.lumnik.jdbc.spi only"
    else
        echo "❌ selftest: check_1d wrongly flagged io.lumnik.jdbc.spi"; ok=0
    fi
    if ( check_1d "$tmp/does-not-exist/src/main/java" ) >/dev/null 2>&1; then
        echo "❌ selftest: check_1d did not fail closed on a scan error (missing directory)"; ok=0
    else
        echo "✓ selftest: check_1d fails closed on a scan error (missing directory)"
    fi

    echo ""
    if [ "$ok" -eq 1 ]; then
        echo "check-bounded-contexts --selftest PASSED"
        return 0
    fi
    echo "check-bounded-contexts --selftest FAILED"
    return 1
}

if [ "${1:-}" = "--selftest" ]; then
    run_selftest
    exit $?
fi

echo "=== check-bounded-contexts ==="

# Check 1: no platform main code imports lumnik-rag types
leaks=$(scan 'io\.lumnik\.rag\.' lumnik-hub/src/main/java)
if [ -n "$leaks" ]; then
    echo "❌ Found imports of io.lumnik.rag.* in lumnik-hub/src/main/java/:"
    echo "$leaks" | sed 's/^/    /'
    fail=1
else
    echo "✓ No io.lumnik.rag.* imports in platform main code"
fi

# Check 1b: TEST code may couple to the closed side, but only under a location or name that
# says so: inside the pro tree (io/lumnik/hub/pro/**, excluded from the open edition by path,
# main and test alike) or — for tests that must live in an open package, like the rag ITs —
# named *ClosedIT. Anything else would break `mvn -DopenEdition test-compile` in the public
# repo. Check 1 above only ever looked at src/main/java, which is how three such tests went
# unnoticed until the boot witness (Act 1).
check_1b lumnik-hub/src/test/java || fail=1

# Check 1c: MAIN code may live on the closed side, but only under a location that says so.
# Pro-only hub code (enterprise auth: SCIM + JIT — Act 1, seam 2) lives under io.lumnik.hub.pro,
# and identity's SCIM-token entity/repository under io.lumnik.identity.scim; the open-edition
# profiles exclude exactly those trees. Open main code must reach them only through the seam
# (ExternalIdentityResolver + the PRE_AUTHENTICATED property, declared in hub.tenant) — a direct
# reference anywhere else would break `mvn -DopenEdition` in the public repo.
check_1c lumnik-hub/src/main/java lumnik-identity/src/main/java || fail=1

# Check 1d: hub main code reaches JDBC dialect providers only through lumnik-jdbc-spi (#857).
check_1d lumnik-hub/src/main/java || fail=1

# Check 2: lumnik-rag Maven dep scope in platform pom
# Since Chantier A2, platform main no longer compile-depends on lumnik-rag
# (the ask ports/types moved to lumnik-extraction-spi). The dep is now
# runtime-scoped: rag is bundled so its chat/ask REST resources
# (RagChatResource, RagSearchResource) are deployed and served alongside hub's
# AskPort impl. With codegen removed, hub main injects no SearchPort at all,
# so a HUB-only (rag-absent) deployment would no longer fail Arc validation.
if grep -B1 -A3 '<artifactId>lumnik-rag' lumnik-hub/pom.xml | grep -qE '<scope>(test|runtime)</scope>'; then
    echo "✓ lumnik-rag is runtime/test-scoped (platform main ⊥ rag — A2)"
elif grep -q '<artifactId>lumnik-rag' lumnik-hub/pom.xml; then
    echo "⚠ lumnik-rag is compile-scoped (platform main can import rag — regression vs A2)"
    # Not a hard fail here, but A2 removed compile-scope; investigate if this fires.
else
    echo "✓ lumnik-rag dep removed entirely"
fi

# Check 2b: lumnik-testkit must be test-scoped in module poms
# The root pom's dependencyManagement entry is exempt (it IS the guard for scope-omitting consumers).
# Exclude lumnik-testkit/pom.xml itself (self-reference in the module's own declaration).
testkit_missing_scope=""
for pom in lumnik-*/pom.xml; do
    if [ ! -f "$pom" ] || [ "$pom" = "lumnik-testkit/pom.xml" ]; then
        continue
    fi
    # Check if this pom declares lumnik-testkit as a dependency (not the module's own artifactId)
    if grep -q '<artifactId>lumnik-testkit' "$pom"; then
        # Verify it has test scope within the dependency block
        if grep -B1 -A3 '<artifactId>lumnik-testkit' "$pom" | grep -qE '<scope>test</scope>'; then
            # OK, test-scoped
            :
        else
            testkit_missing_scope="$testkit_missing_scope$pom"$'\n'
        fi
    fi
done

if [ -n "$testkit_missing_scope" ]; then
    echo "❌ lumnik-testkit must be test-scoped in module poms:"
    printf '%s' "$testkit_missing_scope" | sed 's/^/    /'
    fail=1
else
    echo "✓ lumnik-testkit is test-scoped in module poms (or absent)"
fi

# Check 3: lumnik-extraction-spi module exists
if [ -d lumnik-extraction-spi/src/main/java/io/lumnik/extraction/spi ]; then
    echo "✓ lumnik-extraction-spi module present"
else
    echo "❌ lumnik-extraction-spi module missing or malformed"
    fail=1
fi

# Check 4: no langchain4j imports in platform or rag main code
# The rag scan is gated on the directory: in the OPEN repo (Act 2 extraction) lumnik-rag does
# not exist, and that absence is the expected state — say so explicitly rather than letting
# scan() abort on a missing path. In the monorepo the directory always exists, so the gate
# never silently skips a real scan there.
leaks4a=$(scan 'dev\.langchain4j\.' lumnik-hub/src/main/java)
leaks4b=""
if [ -d lumnik-rag/src/main/java ]; then
    leaks4b=$(scan 'dev\.langchain4j\.' lumnik-rag/src/main/java)
else
    echo "  (lumnik-rag absent — open edition; rag-side langchain scan not applicable)"
fi
if [ -n "$leaks4a" ] || [ -n "$leaks4b" ]; then
    echo "❌ Found dev.langchain4j.* imports outside lumnik-llm-langchain:"
    [ -n "$leaks4a" ] && echo "$leaks4a" | sed 's/^/    /'
    [ -n "$leaks4b" ] && echo "$leaks4b" | sed 's/^/    /'
    fail=1
else
    echo "✓ No dev.langchain4j.* imports in platform/rag main code"
fi

# Check 5: quarkus-langchain4j-* extensions are confined to lumnik-llm-langchain
# Same absence gate as check 4: a missing rag pom in the open repo is expected, and the old
# bare `grep -q … 2>/dev/null` read that absence as "no match" only by accident.
if [ ! -f lumnik-rag/pom.xml ]; then
    if grep -q 'quarkus-langchain4j' lumnik-hub/pom.xml 2>/dev/null; then
        echo "❌ lumnik-hub/pom.xml depends on quarkus-langchain4j-*"
        fail=1
    else
        echo "✓ quarkus-langchain4j-* absent (lumnik-rag not present — open edition)"
    fi
elif grep -q 'quarkus-langchain4j' lumnik-rag/pom.xml 2>/dev/null; then
    echo "❌ lumnik-rag/pom.xml still depends on quarkus-langchain4j-*"
    fail=1
elif grep -q 'quarkus-langchain4j' lumnik-hub/pom.xml 2>/dev/null; then
    echo "❌ lumnik-hub/pom.xml depends on quarkus-langchain4j-*"
    fail=1
else
    echo "✓ quarkus-langchain4j-* confined to lumnik-llm-langchain"
fi

# Check 6: lumnik-llm-spi module exists
if [ ! -d lumnik-llm-spi/src/main/java/io/lumnik/llm/spi ]; then
    echo "❌ lumnik-llm-spi module missing or malformed"
    fail=1
else
    echo "✓ lumnik-llm-spi module present"
fi

# Check 7: hook generator extracted — no hook.generator imports in platform main
leaks7=$(scan 'io\.lumnik\.platform\.hook\.generator' lumnik-hub/src/main/java)
if [ -n "$leaks7" ]; then
    echo "❌ Found io.lumnik.hub.hook.generator references in platform main:"
    echo "$leaks7" | sed 's/^/    /'
    fail=1
else
    echo "✓ No hook.generator references in platform main (codegen extracted)"
fi

# Check 8: @RequiresPermission lives in lumnik-common, not platform
if [ -f lumnik-hub/src/main/java/io/lumnik/platform/security/RequiresPermission.java ]; then
    echo "❌ @RequiresPermission still in lumnik-hub/security/ — should be in lumnik-common"
    fail=1
elif [ ! -f lumnik-common/src/main/java/io/lumnik/common/security/RequiresPermission.java ]; then
    echo "❌ @RequiresPermission not found in lumnik-common/security/"
    fail=1
else
    echo "✓ @RequiresPermission lives in lumnik-common"
fi

# Check 9: no api.rag package references in platform main (rag resources extracted to lumnik-rag)
leaks9=$(scan 'io\.lumnik\.platform\.api\.rag' lumnik-hub/src/main/java)
if [ -n "$leaks9" ]; then
    echo "❌ Found io.lumnik.hub.api.rag references in platform main:"
    echo "$leaks9" | sed 's/^/    /'
    fail=1
else
    echo "✓ No api.rag references in platform main (rag resources extracted)"
fi

# Check 10: no @QuarkusTest on a NESTED class. Failsafe discovers by FILE name (**/*IT.java), so a
# @QuarkusTest on a static inner class is compiled, reported nowhere, and never runs — the suite
# stays green because the test is absent, not because it passes. Two security tests lived that way
# until 2026-08-28: RagResourceIT$WithoutRoleTest (which had also kept a wrong 403 expectation, since
# a dead test cannot fail to correct it) and RagSchemaResourceClosedIT$WithoutAuthTest — that file
# is SchemaCardAdminResourceIT since the schema door moved into the hub (#488). An indented
# @QuarkusTest is the signature; a top-level one starts at column 0.
# The open edition prunes lumnik-rag, so the directory list is built from what EXISTS. scan()
# treats a grep exit >= 2 as a failed search and aborts — correctly: a search that could not run
# must never read as "no violations". Passing it a pruned path turned that honesty into a false
# alarm on every open-edition publish (caught by the publisher's own proof 3, 2026-08-28).
dirs10=""
for d in lumnik-hub/src/test/java lumnik-identity/src/test/java lumnik-rag/src/test/java; do
    [ -d "$d" ] && dirs10="$dirs10 $d"
done
if [ -z "$dirs10" ]; then
    # Why this branch exists at all: scan() with no path operand runs grep with none either,
    # and grep then reads STDIN — the check would hang rather than answer. Unreachable through
    # this script today (check 2 scans lumnik-hub/src/test/java unconditionally and aborts
    # first), so it guards the helper's contract, not a path anyone walks.
    #
    # No "✓" banner here. An earlier draft set fail=1 and then scanned /dev/null, which found
    # nothing and printed a pass — a fail and a pass in the log for the SAME check, and a reader
    # skimming CI output keeps the last one they see. (Caught in review, #340.)
    echo "❌ no test source root found at all — this check cannot run"
    fail=1
else
    # shellcheck disable=SC2086  # word splitting is the point: dirs10 is a path list
    leaks10=$(scan '^[[:space:]]+@QuarkusTest' $dirs10)
    if [ -n "$leaks10" ]; then
        echo "❌ @QuarkusTest on a nested class — failsafe will never run it; move it to its own *IT.java:"
        echo "$leaks10" | sed 's/^/    /'
        fail=1
    else
        echo "✓ No @QuarkusTest on a nested class (every Quarkus test is discoverable by file name)"
    fi
fi

if [ $fail -ne 0 ]; then
    echo ""
    echo "Bounded contexts check FAILED."
    exit 1
fi

echo ""
echo "Bounded contexts check PASSED (source-level boundary enforced; Maven scope tracked as follow-up)."
