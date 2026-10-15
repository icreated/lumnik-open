package generator

import "fmt"

// viewVariant is an adversarial variant for the per-métier view surface
// (POST /api/views/render). The view render is card-backed: a column not in the
// scope's SchemaCard, an unknown scope, a malformed filter — each is refused before
// any row is read. These probe that the honesty line holds at the app layer and
// never 500s, plus RLS on the fused view and the role gate.
type viewVariant struct {
	name           string
	manifestYAML   string // the kind:View body POSTed to /render
	filter         string // optional ?filter= value (empty = none)
	crossTenant    bool   // a tenant-B token renders (RLS must yield 0 rows)
	wrongRole      bool   // a viewer-only token → expect 403
	expectedStatus int    // 200, 400, 403, or 404
	safeRows       bool   // assert no injected SQL is reflected in the rows
	feature        string
}

const viewSalt = uint64(0x51E7)

// validViewManifest resolves against the seeded otk scope card (entity otk_customer
// → connector.v_otk_customer, columns id/name). The filter/RLS/role variants reuse
// it so the ONLY adversarial element is the filter, the tenant, or the role.
const validViewManifest = `apiVersion: apps.lumnik.io/v1
kind: View
metadata:
  name: otk-view
spec:
  scope: otk
  table: connector.v_otk_customer
  title: "OTK"
  columns: [id, name]
`

func viewVariants() []viewVariant {
	return []viewVariant{
		{
			name: "unknown-scope",
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: View
metadata:
  name: otk-ghost-scope
spec:
  scope: otk_ghost_scope
  table: connector.v_otk_customer
  title: "Ghost"
  columns: [id]
`,
			expectedStatus: 404,
			feature:        "render a view on an unknown scope must return 404 (no card — run discovery first)",
		},
		{
			name: "unknown-column",
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: View
metadata:
  name: otk-bad-col
spec:
  scope: otk
  table: connector.v_otk_customer
  title: "BadCol"
  columns: [id, zzz_not_a_column]
`,
			expectedStatus: 400,
			feature:        "render a view naming a column not in the scope card must be refused with 400 (honesty line at the app layer)",
		},
		{
			name: "table-not-in-scope",
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: View
metadata:
  name: otk-bad-table
spec:
  scope: otk
  table: connector.v_ghost_table
  title: "BadTable"
  columns: [id]
`,
			expectedStatus: 400,
			feature:        "render a view whose table is not in the scope must be refused with 400",
		},
		{
			name:           "wrong-kind",
			manifestYAML:   "apiVersion: apps.lumnik.io/v1\nkind: Connector\nmetadata:\n  name: otk-wrong-kind\nspec:\n  scope: otk\n",
			expectedStatus: 400,
			feature:        "render a manifest whose kind is not View must be refused with 400",
		},
		{
			name:           "filter-unknown-column",
			manifestYAML:   validViewManifest,
			filter:         "zzz_not_a_column=1",
			expectedStatus: 400,
			feature:        "render with a filter on a column the view does not show must be refused with 400",
		},
		{
			name:           "filter-malformed",
			manifestYAML:   validViewManifest,
			filter:         "no_operator_here",
			expectedStatus: 400,
			feature:        "render with a malformed filter (no operator) must be refused with 400",
		},
		{
			name:           "filter-sqli",
			manifestYAML:   validViewManifest,
			filter:         "name~'; DROP TABLE--",
			expectedStatus: 200,
			safeRows:       true,
			feature:        "render with SQL injection in a contains-filter must return 200 and safe rows (bound param, no reflection)",
		},
		{
			name:           "cross-tenant-no-card",
			manifestYAML:   validViewManifest,
			crossTenant:    true,
			expectedStatus: 404,
			feature:        "render with a tenant-B token must return 404 — the SchemaCard is tenant-scoped, so a foreign tenant cannot even resolve the scope (isolation at the card layer, before any row is read)",
		},
		{
			name:           "wrong-role",
			manifestYAML:   validViewManifest,
			wrongRole:      true,
			expectedStatus: 403,
			feature:        "render with a viewer-only token must be refused with 403 (lm_user required)",
		},
	}
}

// ViewScenario holds the generated scenario for one seed of the view lane.
type ViewScenario struct {
	Seed           int64
	CaseID         CaseID
	Feature        string
	Variant        string
	ManifestYAML   string
	Filter         string
	CrossTenant    bool
	WrongRole      bool
	ExpectedStatus int
	SafeRows       bool
}

// ViewVariantByName returns the view variant fields for the given name, including
// its catalogue CaseID (see ProcessApplyManifest for why this cannot be recovered
// by the caller itself).
func ViewVariantByName(name string) (manifest, filter string, crossTenant, wrongRole bool, expectedStatus int, safeRows bool, caseID CaseID, found bool) {
	for _, v := range viewVariants() {
		if v.name == name {
			return v.manifestYAML, v.filter, v.crossTenant, v.wrongRole, v.expectedStatus, v.safeRows, viewCaseID(v.name), true
		}
	}
	return "", "", false, false, 0, false, "", false
}

// GenerateView generates an adversarial view-lane scenario for the given seed.
func GenerateView(seed int64) ViewScenario {
	h := splitmix64(uint64(seed) ^ viewSalt)
	variants := viewVariants()
	v := variants[int(splitmix64(h)%uint64(len(variants)))]
	return ViewScenario{
		Seed:           seed,
		CaseID:         viewCaseID(v.name),
		Variant:        v.name,
		ManifestYAML:   v.manifestYAML,
		Filter:         v.filter,
		CrossTenant:    v.crossTenant,
		WrongRole:      v.wrongRole,
		ExpectedStatus: v.expectedStatus,
		SafeRows:       v.safeRows,
		Feature:        renderViewFeature(seed, v),
	}
}

func renderViewFeature(seed int64, v viewVariant) string {
	roleClause := ""
	if v.wrongRole {
		roleClause = " as viewer"
	} else if v.crossTenant {
		roleClause = " as cross-tenant"
	}
	filterClause := ""
	if v.filter != "" {
		filterClause = fmt.Sprintf(" with filter %q", v.filter)
	}
	var thenLines string
	switch {
	case v.expectedStatus == 200 && v.safeRows:
		thenLines = "    Then the view render response status is 200\n    And the view render response is safe"
	case v.expectedStatus == 403:
		thenLines = "    Then the view render response status is 403\n    And wrong-role-view-render must not return 200"
	default:
		thenLines = fmt.Sprintf("    Then the view render response status is %d", v.expectedStatus)
	}
	return fmt.Sprintf(`Feature: view-render %s [seed=%d]
  Scenario: %s
    Given a view manifest for variant %q [seed=%d]
    When I render the view%s%s
%s
`, v.name, seed, v.feature, v.name, seed, filterClause, roleClause, thenLines)
}
