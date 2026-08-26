//go:build otk

package otk

import (
	"fmt"
	"os"
	"os/exec"
	"testing"
)

// The entity-OTK design (2026-06-22, §"Seed entity") made pre-seeding the
// otk_customer entity "the operator's responsibility" — a contract no operator
// ever fulfilled: with OTK_DB_DSN set, the read variants went live and 404'd on
// every real-auth run. The harness now seeds it itself (ratified 2026-07-06),
// idempotently, so the lane is self-contained in CI and locally.
//
// The backing table mirrors the product's dynamic-table shape (DynamicTableWriter):
// tenant_id + ENABLE ROW LEVEL SECURITY + the tenant_isolation policy + grants —
// without the policy the cross-tenant-read variant would see tenant-1 rows and
// report a false RLS breach. Rows exist for tenant 1 only: cross-tenant expects 0.
const seedEntitySQL = `
CREATE TABLE IF NOT EXISTS connector.t_otk_customers (
  tenant_id bigint NOT NULL, id text, name text);
ALTER TABLE connector.t_otk_customers ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy
    WHERE polname = 'tenant_isolation' AND polrelid = 'connector.t_otk_customers'::regclass
  ) THEN
    CREATE POLICY tenant_isolation ON connector.t_otk_customers
      USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
  END IF;
END $$;
DO $$ BEGIN
  GRANT SELECT ON connector.t_otk_customers TO lumnik_app;
EXCEPTION WHEN OTHERS THEN NULL; END $$;
DO $$ BEGIN
  GRANT SELECT ON connector.t_otk_customers TO lumnik_readonly;
EXCEPTION WHEN OTHERS THEN NULL; END $$;
INSERT INTO connector.t_otk_customers (tenant_id, id, name)
  SELECT 1, 'C-1', 'OTK One'
  WHERE NOT EXISTS (SELECT 1 FROM connector.t_otk_customers WHERE tenant_id = 1 AND id = 'C-1');
INSERT INTO connector.t_otk_customers (tenant_id, id, name)
  SELECT 1, 'C-2', 'OTK Two'
  WHERE NOT EXISTS (SELECT 1 FROM connector.t_otk_customers WHERE tenant_id = 1 AND id = 'C-2');
`

// seedEntityManifest is the valid manifest the read variants target
// (readTarget "otk_customer" in the generator catalog). Applying it through the
// real endpoint lets the PRODUCT compile the fused view — the seeder never
// hand-writes what EntityViewCompiler owns.
// scopes: [otk] registers a scope card at apply, giving the view lane a card to
// resolve kind:View manifests against (render/filter honesty + RLS on v_otk_customer).
const seedEntityManifest = `kind: Entity
name: otk_customer
match: [id]
scopes: [otk]
sources:
  - table: t_otk_customers
    map:
      id: id
      name: name
`

// seedDSNAbsent reports whether OTK_DB_DSN is unset — the card-backed read/render
// variants (entity read, view render) are dormant without the seeded scope.
func seedDSNAbsent() bool { return os.Getenv("OTK_DB_DSN") == "" }

// ensureEntityReadSeed makes the read variants' target entity exist.
// Dormant (like the read variants themselves) when OTK_DB_DSN is unset.
// A half-configured environment fails fast and loud — the gold philosophy:
// the badge must never go green by silently skipping what it claims to prove.
func ensureEntityReadSeed(t *testing.T) {
	t.Helper()
	dsn := os.Getenv("OTK_DB_DSN")
	if dsn == "" {
		return // read variants are dormant without the DSN; nothing to seed
	}
	psql, err := exec.LookPath("psql")
	if err != nil {
		t.Fatalf("OTK entity seed: OTK_DB_DSN is set (read variants live) but psql is not on PATH; " +
			"install psql or unset OTK_DB_DSN")
	}
	ddlDSN := envOr("OTK_DB_ADMIN_DSN", dsn)
	// Flag ordering (-X -w -v -d <dsn> -c) mirrors hubSnapshot: options before the
	// DSN for BSD getopt; -w prevents an interactive password prompt hanging CI.
	out, err := exec.Command(psql, "-X", "-w", "-v", "ON_ERROR_STOP=1",
		"-d", ddlDSN, "-c", seedEntitySQL).CombinedOutput()
	if err != nil {
		t.Fatalf("OTK entity seed: backing-table DDL failed (DSN needs DDL rights on schema "+
			"connector — set OTK_DB_ADMIN_DSN): %v\n%s", err, out)
	}
	token, err := currentToken()
	if err != nil {
		t.Fatalf("OTK entity seed: mint integrator token: %v", err)
	}
	status, body, err := entityApplyHTTP([]byte(seedEntityManifest), token)
	if err != nil {
		t.Fatalf("OTK entity seed: POST /api/entities/apply: %v", err)
	}
	if status != 200 {
		t.Fatalf("OTK entity seed: apply otk_customer returned %d (want 200): %s", status, body)
	}
	fmt.Println("  OTK entity seed: otk_customer ready (backing table + fused view)")
}
