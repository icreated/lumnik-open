//go:build otk

package otk

import (
	"strings"
	"testing"
)

// Without OTK_DB_DSN the read variants are dormant, so the seeder must be a
// silent no-op — not an error, and no psql/hub required.
func TestEnsureEntityReadSeed_DormantWithoutDSN(t *testing.T) {
	t.Setenv("OTK_DB_DSN", "")
	ensureEntityReadSeed(t) // must not Fatal
}

// The seed SQL must carry the product's tenant-isolation shape: without the
// policy, cross-tenant-read would see tenant-1 rows and report a false RLS
// breach; rows must exist for tenant 1 only (cross-tenant expects 0 rows).
func TestSeedEntitySQL_CarriesTenantIsolation(t *testing.T) {
	for _, want := range []string{
		"ENABLE ROW LEVEL SECURITY",
		"CREATE POLICY tenant_isolation",
		"current_setting('app.current_tenant', true)::bigint",
	} {
		if !strings.Contains(seedEntitySQL, want) {
			t.Fatalf("seed SQL must contain %q", want)
		}
	}
	if strings.Contains(seedEntitySQL, "SELECT 2,") {
		t.Fatalf("seed SQL must not insert tenant-2 rows (cross-tenant expects 0)")
	}
}

// The seed manifest must use the REAL EntityManifest schema (match:[col] list +
// sources[].map), not the generator's adversarial connector/columns shape which
// the parser rejects with 400.
func TestSeedEntityManifest_UsesRealSchema(t *testing.T) {
	if !strings.Contains(seedEntityManifest, "match: [id]") {
		t.Fatalf("seed manifest must use the real match:[col] list shape")
	}
	if !strings.Contains(seedEntityManifest, "map:") {
		t.Fatalf("seed manifest must use sources[].map (real schema)")
	}
	if strings.Contains(seedEntityManifest, "connector:") || strings.Contains(seedEntityManifest, "columns:") {
		t.Fatalf("seed manifest must not use the adversarial connector/columns shape (parser 400s it)")
	}
	// The view lane resolves kind:View manifests against the otk scope card; the seed
	// must register it.
	if !strings.Contains(seedEntityManifest, "scopes: [otk]") {
		t.Fatalf("seed manifest must declare scopes: [otk] (the view lane needs the card)")
	}
}
