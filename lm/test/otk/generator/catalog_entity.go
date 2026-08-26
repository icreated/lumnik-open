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
sources:
  - connector: otk_erp
    table: t_customers
    columns: [id, name]
match: []
`,
			feature: "apply manifest with empty match must be rejected with 400",
		},
		{
			name:           "reserved-column",
			expectedStatus: 400,
			manifestYAML: `kind: Entity
name: Reserved
sources:
  - connector: otk_erp
    table: t_customers
    columns: [id, _conflicts]
match:
  - source: otk_erp
    column: id
`,
			feature: "apply manifest with reserved column _conflicts must be rejected with 400",
		},
		{
			name:           "missing-coverage",
			expectedStatus: 400,
			manifestYAML: `kind: Entity
name: MissingCov
sources:
  - connector: otk_erp
    table: t_customers
    columns: [name]
match:
  - source: otk_erp
    column: id
`,
			feature: "apply manifest where match column is absent from columns must be rejected with 400",
		},
		{
			name:           "wrong-role-apply",
			expectedStatus: 403,
			useUserRole:    true,
			manifestYAML: `kind: Entity
name: WrongRole
sources:
  - connector: otk_erp
    table: t_customers
    columns: [id, name]
match:
  - source: otk_erp
    column: id
`,
			feature: "apply with lm_user role must be rejected with 403 (not 200)",
		},
		// weird
		{
			name:           "huge-name",
			expectedStatus: 0,
			manifestYAML: fmt.Sprintf(`kind: Entity
name: %s
sources:
  - connector: otk_erp
    table: t_customers
    columns: [id, name]
match:
  - source: otk_erp
    column: id
`, strings.Repeat("X", 5000)),
			feature: "apply manifest with 5000-char name must never cause a 500",
		},
		{
			name:           "no-sources",
			expectedStatus: 400,
			manifestYAML: `kind: Entity
name: NoSources
sources: []
match:
  - source: otk_erp
    column: id
`,
			feature: "apply manifest with empty sources must be rejected with 400",
		},
		// textual
		{
			name:           "zero-width-name",
			expectedStatus: 0,
			manifestYAML:   "kind: Entity\nname: Cust\u200Bomer\nsources:\n  - connector: otk_erp\n    table: t_customers\n    columns: [id, name]\nmatch:\n  - source: otk_erp\n    column: id\n",
			feature:        "apply manifest with zero-width space in name must never cause a 500",
		},
		// hack
		{
			name:           "sqli-in-name",
			expectedStatus: 0,
			manifestYAML: `kind: Entity
name: "cust'; DROP TABLE entity_manifest--"
sources:
  - connector: otk_erp
    table: t_customers
    columns: [id, name]
match:
  - source: otk_erp
    column: id
`,
			feature: "apply manifest with SQL injection in name must never cause a 500 (quoteIdent must be used)",
		},
		{
			name:           "sqli-in-table",
			expectedStatus: 0,
			manifestYAML: `kind: Entity
name: SqliTable
sources:
  - connector: otk_erp
    table: "t'; DROP TABLE entity_manifest--"
    columns: [id, name]
match:
  - source: otk_erp
    column: id
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

// EntityScenario holds the generated scenario for one seed.
type EntityScenario struct {
	Seed    int64
	SubType string // "apply" or "read"
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

// EntityApplyManifest returns the manifest YAML, useUserRole, expectedStatus, and found
// for the named apply variant. Returns ("", false, 0, false) if not found.
func EntityApplyManifest(name string) (yaml string, useUserRole bool, expectedStatus int, found bool) {
	for _, v := range entityApplyVariants() {
		if v.name == name {
			return v.manifestYAML, v.useUserRole, v.expectedStatus, true
		}
	}
	return "", false, 0, false
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
