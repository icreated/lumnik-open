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
# scan first, filter second — deliberately NOT `scan … | grep -v …`: inside a pipeline scan's
# abort is swallowed (the pipeline reports grep -v's status), which would re-open the very hole
# this helper closes. The `|| true` below is on grep -v, whose exit 1 just means "all filtered".
closed_coupled=$(scan 'io\.lumnik\.rag\.|dev\.langchain4j\.|io\.lumnik\.llm\.langchain\.|io\.lumnik\.hub\.pro\.|io\.lumnik\.identity\.scim\.' \
    lumnik-hub/src/test/java)
# -E, not BRE \|: a mid-pattern $ before \| is implementation-defined in BRE (ugrep reads the
# first alternative as literal text, GNU as an anchor) — ERE alternation behaves the same
# everywhere this runs.
mistests=$(printf '%s' "$closed_coupled" | grep -Ev 'ClosedIT\.java$|/hub/pro/' || true)
if [ -n "$mistests" ]; then
    echo "❌ Test sources import closed-edition types but are not named *ClosedIT.java:"
    echo "$mistests" | sed 's/^/    /'
    echo "    Rename them to *ClosedIT.java (they travel with the closed side), or drop the import."
    fail=1
else
    echo "✓ Closed-coupled test sources are all named *ClosedIT (excluded from the open edition)"
fi

# Check 1c: MAIN code may live on the closed side, but only under a location that says so.
# Pro-only hub code (enterprise auth: SCIM + JIT — Act 1, seam 2) lives under io.lumnik.hub.pro,
# and identity's SCIM-token entity/repository under io.lumnik.identity.scim; the open-edition
# profiles exclude exactly those trees. Open main code must reach them only through the seam
# (ExternalIdentityResolver + the PRE_AUTHENTICATED property, declared in hub.tenant) — a direct
# reference anywhere else would break `mvn -DopenEdition` in the public repo.
# Same shape as 1b: scan into a variable, filter after (never pipe scan).
pro_refs=$(scan 'io\.lumnik\.hub\.pro\.|io\.lumnik\.identity\.scim\.' \
    lumnik-hub/src/main/java lumnik-identity/src/main/java)
open_pro_refs=$(printf '%s' "$pro_refs" | grep -Ev '/hub/pro/|/identity/scim/' || true)
if [ -n "$open_pro_refs" ]; then
    echo "❌ Open main sources reference pro-only code (io.lumnik.hub.pro / io.lumnik.identity.scim):"
    echo "$open_pro_refs" | sed 's/^/    /'
    echo "    Route it through the ExternalIdentityResolver seam, or move the file under the pro tree."
    fail=1
else
    echo "✓ Pro-only main code is referenced only from within the pro trees (open edition excludes them)"
fi

# Check 2: lumnik-rag Maven dep scope in platform pom
# Since Chantier A2, platform main no longer compile-depends on lumnik-rag
# (the ask ports/types moved to lumnik-extraction-spi). The dep is now
# runtime-scoped: rag is bundled so its chat/ask REST resources
# (RagChatResource, RagSchemaResource) are deployed and served alongside hub's
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
# a dead test cannot fail to correct it) and RagSchemaResourceClosedIT$WithoutAuthTest. An indented
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
