package generator

import (
	"fmt"
	"strings"
)

// entityApplyVariant represents a named apply-sub-type adversarial variant.
type entityApplyVariant struct {
	name           string
	manifestYAML   string
	expectedStatus int // 400, 403, or 0 (means "not 500")
	useUserRole    bool
	feature        string
}

// entityReadVariant represents a named read-sub-type adversarial variant.
type entityReadVariant struct {
	name        string
	readTarget  string // entity name to GET
	readQuery   string // query string appended to GET URL
	crossTenant bool   // tenant-B token reads tenant-A entity
	feature     string
}

// entityCliVariant represents a named CLI-sub-type happy-path variant (#647): proving the five
// #623 CLI doors (lm entity resolve/ambiguous/history/assert-same/assert-distinct) work
// end-to-end against a genuine ambiguity, not merely each door's request/response shape. Not the
// adversarial battery over the REST doors directly — that is #643's entityres lane's job.
type entityCliVariant struct {
	name    string
	feature string
}

const entitySalt = uint64(0xE117)

// entityApplyVariants returns all apply variants in category order.
// Categories: logical, weird, textual, hack
func entityApplyVariants() []entityApplyVariant {
	return []entityApplyVariant{
		// logical
		{
			name:           "wrong-kind",
			expectedStatus: 400,
			manifestYAML: `kind: Connector
name: ShouldFail
`,
			feature: "apply wrong-kind manifest must be rejected with 400",
		},
		{
			name:           "no-match",
			expectedStatus: 400,
			manifestYAML: `kind: Entity
name: NoMatch
match: []
sources:
  - table: t_customers
    map:
      id: id
      name: name
`,
			feature: "apply manifest with empty match must be rejected with 400",
		},
		{
			name:           "reserved-column",
			expectedStatus: 400,
			manifestYAML: `kind: Entity
name: Reserved
match: [id]
sources:
  - table: t_customers
    map:
      id: id
      bad: _conflicts
`,
			feature: "apply manifest with reserved column _conflicts must be rejected with 400",
		},
		{
			name:           "missing-coverage",
			expectedStatus: 400,
			manifestYAML: `kind: Entity
name: MissingCov
match: [id]
sources:
  - table: t_customers
    map:
      name: name
`,
			feature: "apply manifest where match column is absent from a source's map must be rejected with 400",
		},
		{
			name:           "wrong-role-apply",
			expectedStatus: 403,
			useUserRole:    true,
			manifestYAML: `kind: Entity
name: WrongRole
match: [id]
sources:
  - table: t_customers
    map:
      id: id
      name: name
`,
			feature: "apply with lm_user role must be rejected with 403 (not 200)",
		},
		// weird
		{
			name:           "huge-name",
			expectedStatus: 0,
			manifestYAML: fmt.Sprintf(`kind: Entity
name: %s
match: [id]
sources:
  - table: t_customers
    map:
      id: id
      name: name
`, strings.Repeat("X", 5000)),
			feature: "apply manifest with 5000-char name must never cause a 500",
		},
		{
			name:           "no-sources",
			expectedStatus: 400,
			manifestYAML: `kind: Entity
name: NoSources
match: [id]
sources: []
`,
			feature: "apply manifest with empty sources must be rejected with 400",
		},
		// textual
		{
			name:           "zero-width-name",
			expectedStatus: 0,
			manifestYAML:   "kind: Entity\nname: Cust\u200Bomer\nmatch: [id]\nsources:\n  - table: t_customers\n    map:\n      id: id\n      name: name\n",
			feature:        "apply manifest with zero-width space in name must never cause a 500",
		},
		// hack
		{
			name:           "sqli-in-name",
			expectedStatus: 0,
			manifestYAML: `kind: Entity
name: "cust'; DROP TABLE entity_manifest--"
match: [id]
sources:
  - table: t_customers
    map:
      id: id
      name: name
`,
			feature: "apply manifest with SQL injection in name must never cause a 500 (quoteIdent must be used)",
		},
		{
			name:           "sqli-in-table",
			expectedStatus: 0,
			manifestYAML: `kind: Entity
name: SqliTable
match: [id]
sources:
  - table: "t'; DROP TABLE entity_manifest--"
    map:
      id: id
      name: name
`,
			feature: "apply manifest with SQL injection in table name must never cause a 500 (quoteIdent must be used)",
		},
	}
}

// entityReadVariants returns all read-sub-type variants.
func entityReadVariants() []entityReadVariant {
	return []entityReadVariant{
		{
			name:       "unknown-entity",
			readTarget: "ghost_otk_seed",
			readQuery:  "",
			feature:    "GET unknown entity must return 404",
		},
		{
			name:       "sqli-in-filter",
			readTarget: "otk_customer",
			readQuery:  "name=%27%3B+DROP+TABLE--",
			feature:    "GET with SQL injection in canonical column filter must return 200 and safe rows (no injected content)",
		},
		{
			name:       "huge-limit",
			readTarget: "otk_customer",
			readQuery:  "limit=999999",
			feature:    "GET with huge limit must return 200 and at most 1000 rows",
		},
		{
			name:        "cross-tenant-read",
			readTarget:  "otk_customer",
			readQuery:   "",
			crossTenant: true,
			feature:     "GET entity with tenant-B token must return 0 rows (RLS isolation enforced)",
		},
	}
}

// entityCliVariants returns all CLI-sub-type variants.
func entityCliVariants() []entityCliVariant {
	return []entityCliVariant{
		{
			name: "resolve-ambiguous-assert-same-assert-distinct-history",
			feature: "lm entity resolve/ambiguous/assert-same/assert-distinct/history clear two " +
				"genuine ambiguities end-to-end",
		},
	}
}

// EntityScenario holds the generated scenario for one seed.
type EntityScenario struct {
	Seed    int64
	CaseID  CaseID
	SubType string // "apply", "read" or "cli"
	Feature string // Gherkin feature text
	// apply fields
	ManifestYAML string
	Variant      string // variant name (e.g. "sqli-in-name"), not the entity name in the manifest
	UseUserRole  bool
	// read fields
	ReadTarget  string
	ReadQuery   string
	CrossTenant bool
}

// renderEntityApplyFeature generates the Gherkin feature text for an apply scenario.
func renderEntityApplyFeature(seed int64, v entityApplyVariant) string {
	roleClause := "lm_admin"
	if v.useUserRole {
		roleClause = "lm_user"
	}
	var thenLine string
	switch v.expectedStatus {
	case 400:
		thenLine = "    Then the entity apply response status is 400"
	case 403:
		thenLine = "    Then the entity apply response status is 403\n    And wrong-role-apply must not return 200"
	default:
		thenLine = "    Then the entity apply response status is not 500"
	}
	return fmt.Sprintf(`Feature: entity-apply %s [seed=%d]
  Scenario: %s
    Given an entity manifest for variant %q [seed=%d]
    When I apply the entity manifest as %s
%s
`, v.name, seed, v.feature, v.name, seed, roleClause, thenLine)
}

// EntityApplyManifest returns the manifest YAML, useUserRole, expectedStatus, its
// catalogue CaseID, and found for the named apply variant.
// Returns ("", false, 0, "", false) if not found.
func EntityApplyManifest(name string) (yaml string, useUserRole bool, expectedStatus int, caseID CaseID, found bool) {
	for _, v := range entityApplyVariants() {
		if v.name == name {
			return v.manifestYAML, v.useUserRole, v.expectedStatus, entityCaseID("apply", v.name), true
		}
	}
	return "", false, 0, "", false
}

// EntityReadVariantByName returns the read variant fields for the given name.
// Returns ("", "", false, false) if not found.
func EntityReadVariantByName(name string) (readTarget, readQuery string, crossTenant, found bool) {
	for _, v := range entityReadVariants() {
		if v.name == name {
			return v.readTarget, v.readQuery, v.crossTenant, true
		}
	}
	return "", "", false, false
}

// EntityReadCaseID resolves the catalogue CaseID for a read-lane scenario from the
// fields the When step actually observes — readTarget, readQuery and crossTenant —
// rather than the variant name, which the Given step's Gherkin line never carries
// (only the seed entity name is captured there; see entityReadVariants, whose
// (readTarget, readQuery, crossTenant) tuple is unique per variant today).
func EntityReadCaseID(readTarget, readQuery string, crossTenant bool) (CaseID, bool) {
	for _, v := range entityReadVariants() {
		if v.readTarget == readTarget && v.readQuery == readQuery && v.crossTenant == crossTenant {
			return entityCaseID("read", v.name), true
		}
	}
	return "", false
}

// renderEntityReadFeature generates the Gherkin feature text for a read scenario.
func renderEntityReadFeature(seed int64, v entityReadVariant) string {
	var thenLine string
	switch v.name {
	case "unknown-entity":
		thenLine = "    Then the entity read response status is 404"
	case "sqli-in-filter":
		thenLine = "    Then the entity read response status is 200\n    And the entity read response is safe"
	case "huge-limit":
		thenLine = "    Then the entity read response status is 200\n    And the entity read response has at most 1000 rows"
	case "cross-tenant-read":
		thenLine = "    Then the entity read response status is 200\n    And the entity read response contains no rows (RLS)"
	default:
		thenLine = "    Then the entity read response status is 200"
	}
	tenantClause := ""
	if v.crossTenant {
		tenantClause = " as cross-tenant"
	}
	return fmt.Sprintf(`Feature: entity-read %s [seed=%d]
  Scenario: %s
    Given the seed entity %q exists for entity read [seed=%d]
    When I read entity %q with query %q%s
%s
`, v.name, seed, v.feature, v.readTarget, seed, v.readTarget, v.readQuery, tenantClause, thenLine)
}

// EntityCliCaseID resolves the catalogue CaseID for the named CLI-sub-type variant.
// Returns ("", false) if not found.
func EntityCliCaseID(name string) (CaseID, bool) {
	for _, v := range entityCliVariants() {
		if v.name == name {
			return entityCaseID("cli", v.name), true
		}
	}
	return "", false
}

// renderEntityCliFeature generates the Gherkin feature text for a CLI-sub-type scenario. Unlike
// apply/read, the live steps drive `lm entity` subcommands directly (runner.Run), not raw HTTP —
// that IS the surface #647 exists to prove.
func renderEntityCliFeature(seed int64, v entityCliVariant) string {
	return fmt.Sprintf(`Feature: entity-cli %s [seed=%d]
  Scenario: %s
    Given the entity CLI fixture is seeded with two genuine ambiguities [seed=%d]
    When I drive the entity resolution CLI doors end-to-end
    Then lm entity ambiguous no longer lists either fixture identity
    And lm entity history shows both fixture identities were cleared by an assertion
`, v.name, seed, v.feature, seed)
}
