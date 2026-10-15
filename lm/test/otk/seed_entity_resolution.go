//go:build otk

package otk

import (
	"fmt"
	"os"
	"os/exec"
	"testing"
)

// The entity-resolution lane (#643, part of #613's arc) needs its own backing table and its
// own resolution-bearing manifest — otk_customer (seed_entity.go) declares match:, not
// resolution:, and the two are mutually exclusive on one manifest (EntityResolutionManifestParserTest
// .rejects_match_and_resolution_together). Same posture as seed_entity.go throughout: DDL via
// psql on the admin DSN, the manifest applied once through the real /api/entities/apply endpoint
// (never hand-written into the DB — EntityViewCompiler/EntityResolutionPass own that), dormant
// without OTK_DB_DSN, idempotent so a CI rerun never fails on "already exists".
// id is a real requirement, not decoration: EntityResolutionPass.paginateByPk keyset-paginates
// every source table on a monotonic "id" column (src.id > ?, ORDER BY ... src.id DESC) — the
// same system column DynamicTableWriter.createTableDdl stamps onto every real ingested table
// (id BIGSERIAL PRIMARY KEY). (tenant_id, src_id) stays this fixture's OWN upsert key.
var seedEntityResolutionSQL = resTableDDL("t_otk_res_source") + resTableDDL("t_otk_res_source_b")

// resTableDDL is one physical source table's DDL, parameterized so the fixture can declare a
// second source (t_otk_res_source_b) — needed because core.entity_identity's own
// uq_entity_identity_cardinality constraint ("a canonical entity holds at most one identity per
// source") makes two DIFFERENT rows of the SAME source auto-merging into ONE entity structurally
// impossible: Postgres rejects the second upsertIdentity outright rather than the resolver ever
// getting a chance to decide anything. Production fusion (many_source_rows_sharing_one_entity_are_
// not_ambiguous in CascadeResolverTest.java) always crosses sources (erp + crm) for exactly this
// reason — case 2's "two rows share a mailbox and auto-fuse" fixture must too.
func resTableDDL(table string) string {
	return fmt.Sprintf(`
CREATE TABLE IF NOT EXISTS connector.%[1]s (
  id bigserial NOT NULL, tenant_id bigint NOT NULL, src_id text, email text,
  PRIMARY KEY (tenant_id, src_id));
ALTER TABLE connector.%[1]s ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policy
    WHERE polname = 'tenant_isolation' AND polrelid = 'connector.%[1]s'::regclass
  ) THEN
    CREATE POLICY tenant_isolation ON connector.%[1]s
      USING (tenant_id = current_setting('app.current_tenant', true)::bigint);
  END IF;
END $$;
DO $$ BEGIN
  GRANT SELECT ON connector.%[1]s TO lumnik_app;
EXCEPTION WHEN OTHERS THEN NULL; END $$;
DO $$ BEGIN
  GRANT SELECT ON connector.%[1]s TO lumnik_readonly;
EXCEPTION WHEN OTHERS THEN NULL; END $$;
`, table)
}

// seedEntityResolutionManifest declares one exact cascade rule on email over TWO physical
// sources — the minimum needed to exercise ambiguity, assertion-veto durability and the "never
// silently recycle a resolved link" guard (CascadeResolver's own javadoc) over HTTP. No scopes: —
// this fixture never renders a view.
// The physical column is src_id, not source_id: EntityViewCompiler's resolved-view join
// (appendResolvedSourceBranch) writes "ei.source_id = <identitySrcCol>" unqualified against the
// FROM table, so a physical column also named source_id collides with core.entity_identity's own
// column of that name — "column reference is ambiguous" from Postgres, not a validation error.
const seedEntityResolutionManifest = `kind: Entity
name: otk_res_customer
resolution:
  identity:
    t_otk_res_source: source_id
    t_otk_res_source_b: source_id
  cascade:
    - id: email
      exact:
        fields: [email]
sources:
  - table: t_otk_res_source
    map:
      src_id: source_id
      email: email
  - table: t_otk_res_source_b
    map:
      src_id: source_id
      email: email
`

// ensureEntityResolutionSeed makes the resolution-bearing manifest and its backing table exist.
// Dormant without OTK_DB_DSN, like every other DB-truth-backed fixture — the lane's own Given
// steps are dormant the same way, so a half-configured environment fails fast and loud rather
// than silently skipping what the badge claims to prove (same rationale as ensureEntityReadSeed).
func ensureEntityResolutionSeed(t *testing.T) {
	t.Helper()
	dsn := os.Getenv("OTK_DB_DSN")
	if dsn == "" {
		return
	}
	psql, err := exec.LookPath("psql")
	if err != nil {
		t.Fatalf("OTK entity-resolution seed: OTK_DB_DSN is set but psql is not on PATH; " +
			"install psql or unset OTK_DB_DSN")
	}
	ddlDSN := envOr("OTK_DB_ADMIN_DSN", dsn)
	out, err := exec.Command(psql, "-X", "-w", "-v", "ON_ERROR_STOP=1",
		"-d", ddlDSN, "-c", seedEntityResolutionSQL).CombinedOutput()
	if err != nil {
		t.Fatalf("OTK entity-resolution seed: backing-table DDL failed (DSN needs DDL rights on "+
			"schema connector — set OTK_DB_ADMIN_DSN): %v\n%s", err, out)
	}
	token, err := currentToken()
	if err != nil {
		t.Fatalf("OTK entity-resolution seed: mint integrator token: %v", err)
	}
	status, body, err := entityApplyHTTP([]byte(seedEntityResolutionManifest), token)
	if err != nil {
		t.Fatalf("OTK entity-resolution seed: POST /api/entities/apply: %v", err)
	}
	if status != 200 {
		t.Fatalf("OTK entity-resolution seed: apply otk_res_customer returned %d (want 200): %s", status, body)
	}
	fmt.Println("  OTK entity-resolution seed: otk_res_customer ready (backing table + resolution manifest)")
}
