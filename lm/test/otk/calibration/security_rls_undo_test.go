package calibration

import (
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// migrationVersionRe pulls the Flyway version number out of a migration file name
// (VNN__description.sql).
var migrationVersionRe = regexp.MustCompile(`^V(\d+)__`)

// touchesConnectorConnectorPolicy matches an ALTER/CREATE POLICY on
// connector.connector specifically — \b excludes connector.connector_cursor,
// connector.connector_run and the rest of the family, which share the prefix.
var touchesConnectorConnectorPolicy = regexp.MustCompile(`(?:ALTER|CREATE) POLICY \S+ ON connector\.connector\b`)

// TestSecurityRLSIsolationDroppedUndoMatchesCurrentPolicy is the regression a PR
// review on this fault demanded: a first draft undid to V9's ORIGINAL predicate
// (`tenant_id = current_setting(...)::bigint`), but V21 wrapped it in NULLIF to
// survive an empty GUC and V22 added the '0' system-mode branch — undoing to V9
// would have printed "SQL fault undone" while PERMANENTLY regressing both fixes on
// whatever database the calibration ran against.
//
// The bug was never "I mistyped the predicate" — it was "I copied it from the
// first migration I found and never checked whether a LATER one also touched this
// policy". So the guard that matters is not "does the artefact match V22" (that
// alone would have passed a review copying from V9 just as confidently); it is
// "is there anything AFTER the migration the artefact was written against that
// also touches this table's policy" — which is exactly the question nobody asked
// the first time.
func TestSecurityRLSIsolationDroppedUndoMatchesCurrentPolicy(t *testing.T) {
	root, err := exec.Command("git", "rev-parse", "--show-toplevel").Output()
	if err != nil {
		t.Skipf("not in a git checkout: %v", err)
	}
	migrationBase := filepath.Join(strings.TrimSpace(string(root)),
		"lumnik-hub", "src", "main", "resources", "db", "migration")

	// core, connector and platform share ONE Flyway version namespace (see
	// CLAUDE.md) — a migration touching this table's policy could land in any of
	// the three, so all three are scanned, not just "connector".
	const canonicalMigration = "V22__connector_system_access.sql"
	canonicalVersion, err := strconv.Atoi(migrationVersionRe.FindStringSubmatch(canonicalMigration)[1])
	if err != nil {
		t.Fatalf("canonicalMigration %q does not carry a parseable version: %v", canonicalMigration, err)
	}

	var newer []string
	foundCanonical := false
	for _, dir := range []string{"core", "connector", "platform"} {
		full := filepath.Join(migrationBase, dir)
		entries, err := os.ReadDir(full)
		if err != nil {
			t.Fatalf("read migration dir %s: %v", full, err)
		}
		for _, e := range entries {
			m := migrationVersionRe.FindStringSubmatch(e.Name())
			if m == nil {
				continue
			}
			v, err := strconv.Atoi(m[1])
			if err != nil {
				continue
			}
			b, err := os.ReadFile(filepath.Join(full, e.Name()))
			if err != nil {
				t.Fatalf("read migration %s: %v", e.Name(), err)
			}
			if !touchesConnectorConnectorPolicy.Match(b) {
				continue
			}
			if e.Name() == canonicalMigration {
				foundCanonical = true
				continue
			}
			if v > canonicalVersion {
				newer = append(newer, e.Name())
			}
		}
	}
	if !foundCanonical {
		t.Fatalf("%s no longer exists (renamed, removed, or moved) — find whatever now defines "+
			"connector.connector's tenant_isolation policy and update BOTH this test's "+
			"canonicalMigration and faults/security-rls-isolation-dropped.sql's undo half", canonicalMigration)
	}
	if len(newer) > 0 {
		t.Fatalf("migration(s) newer than %s also touch connector.connector's RLS policy: %v — "+
			"the canonical predicate has moved again since this test was written; update "+
			"canonicalMigration here AND faults/security-rls-isolation-dropped.sql's undo half "+
			"to match whatever the newest one now says, exactly the mistake this test exists to catch",
			canonicalMigration, newer)
	}

	canonical, err := os.ReadFile(filepath.Join(migrationBase, "connector", canonicalMigration))
	if err != nil {
		t.Fatalf("read %s: %v", canonicalMigration, err)
	}
	// The two clauses V21/V22 each added, checked independently of exact
	// whitespace: the artefact's undo is one line, the migration's is wrapped.
	for _, want := range []string{
		`current_setting('app.current_tenant', true) = '0'`,
		`NULLIF(current_setting('app.current_tenant', true), '')::bigint`,
	} {
		if !strings.Contains(string(canonical), want) {
			t.Fatalf("%s no longer contains %q — this test's own understanding of the canonical "+
				"policy is stale; re-derive it from the migration and update the fault artefact to match",
				canonicalMigration, want)
		}
	}

	_, undo, err := readSQLFault(filepath.Join(FaultsDir, "security-rls-isolation-dropped.sql"))
	if err != nil {
		t.Fatalf("readSQLFault: %v", err)
	}
	for _, want := range []string{
		`current_setting('app.current_tenant', true) = '0'`,
		`NULLIF(current_setting('app.current_tenant', true), '')::bigint`,
	} {
		if !strings.Contains(undo, want) {
			t.Fatalf("faults/security-rls-isolation-dropped.sql's undo does not restore the "+
				"CURRENT canonical policy (%s) — missing %q\n  got: %s", canonicalMigration, want, undo)
		}
	}
}
