package generator

import "fmt"

// processApplyVariant is a governance-refusal apply variant for POST /api/processes/apply.
// The kind:Process apply is where the honesty line rises to the event layer: a process
// that could never fire, or would pollute the governed decision.* namespace, is refused
// at apply — before it ever runs. These probe that the refusals hold and never 500.
type processApplyVariant struct {
	name           string
	manifestYAML   string
	expectedStatus int // 400, 403, or 0 (means "not 500")
	useUserRole    bool
	feature        string
}

// inboxVariant is an adversarial variant for the human-attention surface
// (GET /api/inbox, POST /api/inbox/{id}/ack). op is "read" or "ack".
type inboxVariant struct {
	name           string
	op             string // "read" | "ack"
	query          string // for read: the query string; for ack: the {id} path segment
	crossTenant    bool   // a tenant-B token exercises the endpoint
	wrongRole      bool   // a viewer-only token (no lm_user) → expect 403
	expectedStatus int    // 200, 403, or 404
	maxRows        int    // for read huge-limit: the server clamp to assert (0 = no row cap)
	feature        string
}

const processSalt = uint64(0xDEC1)

// A valid Process manifest, the base the wrong-role variant reuses (it must be
// well-formed so the ONLY reason for a 403 is the role, not the shape).
const validProcessManifest = `apiVersion: apps.lumnik.io/v1
kind: Process
metadata:
  name: otk-valid-process
spec:
  scope: demo
  on:
    event: data.row.changed
    table: connector.t_cust_csv
  when:
    - ville=Lyon
  emit:
    type: decision.otk-valid
    message: "otk valid decision"
`

// processApplyVariants returns the governance-refusal catalog. Every refusal is a
// promise the substrate makes about the governed event namespace.
func processApplyVariants() []processApplyVariant {
	return []processApplyVariant{
		{
			name:           "emit-not-decision",
			expectedStatus: 400,
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: Process
metadata:
  name: otk-bad-emit
spec:
  scope: demo
  on:
    event: data.row.changed
    table: connector.t_cust_csv
  when:
    - ville=Lyon
  emit:
    type: webhook.fire
    message: "escapes the taxonomy"
`,
			feature: "apply a process emitting a non-decision.* type must be refused with 400 (governed taxonomy)",
		},
		{
			name:           "unknown-event",
			expectedStatus: 400,
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: Process
metadata:
  name: otk-bad-event
spec:
  scope: demo
  on:
    event: data.row.deleted
    table: connector.t_cust_csv
  when:
    - ville=Lyon
  emit:
    type: decision.otk
    message: "unknown trigger"
`,
			feature: "apply a process on an unknown trigger event must be refused with 400 (data.row.changed is the only v1 trigger)",
		},
		{
			name:           "empty-when",
			expectedStatus: 400,
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: Process
metadata:
  name: otk-empty-when
spec:
  scope: demo
  on:
    event: data.row.changed
    table: connector.t_cust_csv
  when: []
  emit:
    type: decision.otk
    message: "no condition"
`,
			feature: "apply a process with an empty when: must be refused with 400 (a condition is required)",
		},
		{
			name:           "blank-scope",
			expectedStatus: 400,
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: Process
metadata:
  name: otk-no-scope
spec:
  on:
    event: data.row.changed
    table: connector.t_cust_csv
  when:
    - ville=Lyon
  emit:
    type: decision.otk
    message: "no scope"
`,
			feature: "apply a process with no scope must be refused with 400 (spec.scope is required)",
		},
		{
			name:           "sqli-in-name",
			expectedStatus: 400,
			manifestYAML: `apiVersion: apps.lumnik.io/v1
kind: Process
metadata:
  name: "otk'; DROP TABLE process_manifest--"
spec:
  scope: demo
  on:
    event: data.row.changed
    table: connector.t_cust_csv
  when:
    - ville=Lyon
  emit:
    type: decision.otk
    message: "sqli name"
`,
			feature: "apply a process whose name carries SQL injection must be refused with 400 by the identifier whitelist and never 500",
		},
		{
			name:           "wrong-role",
			expectedStatus: 403,
			useUserRole:    true,
			manifestYAML:   validProcessManifest,
			feature:        "apply a valid process as lm_user (not lm_admin) must be refused with 403 (no role escalation)",
		},
	}
}

// inboxVariants returns the attention-surface adversarial catalog. The read and ack
// variants are seed-free: they probe clamps, role gates, and honest 404s that hold
// regardless of inbox contents (the DB-seeded cross-tenant isolation proof lives in
// InboxResourceIT, not duplicated here).
func inboxVariants() []inboxVariant {
	return []inboxVariant{
		{
			name:           "huge-limit",
			op:             "read",
			query:          "limit=999999",
			expectedStatus: 200,
			maxRows:        200, // the server clamp (InboxResource: 1..200)
			feature:        "GET /api/inbox with a huge limit must return 200 and at most 200 rows (server clamp)",
		},
		{
			name:           "cross-tenant-read",
			op:             "read",
			query:          "",
			crossTenant:    true,
			expectedStatus: 200,
			feature:        "GET /api/inbox with a tenant-B token must return 200 and a safe JSON array (never another tenant's rows, never 500)",
		},
		{
			name:           "wrong-role-read",
			op:             "read",
			query:          "",
			wrongRole:      true,
			expectedStatus: 403,
			feature:        "GET /api/inbox with a viewer-only token must be refused with 403 (lm_user required)",
		},
		{
			name:           "ack-foreign",
			op:             "ack",
			query:          "999999999",
			expectedStatus: 404,
			feature:        "POST /api/inbox/{id}/ack for an id that is not the caller-tenant's decision must return 404 and leak nothing",
		},
		{
			name:           "ack-wrong-role",
			op:             "ack",
			query:          "999999999",
			wrongRole:      true,
			expectedStatus: 403,
			feature:        "POST /api/inbox/{id}/ack with a viewer-only token must be refused with 403 (lm_user required)",
		},
	}
}

// ProcessScenario holds the generated scenario for one seed of the process lane.
type ProcessScenario struct {
	Seed    int64
	CaseID  CaseID
	SubType string // "apply" or "inbox"
	Feature string
	// apply fields
	ManifestYAML string
	Variant      string
	UseUserRole  bool
	// inbox fields
	InboxOp        string // "read" | "ack"
	InboxQuery     string
	CrossTenant    bool
	WrongRole      bool
	ExpectedStatus int
	MaxRows        int
}

// ProcessApplyManifest returns the fields for the named apply variant, including
// its catalogue CaseID — the identity a Given step cannot otherwise recover, since
// the constructor (processCaseID) is unexported and lives with the rest of the
// catalogue's own identity in caseid.go.
func ProcessApplyManifest(name string) (yaml string, useUserRole bool, expectedStatus int, caseID CaseID, found bool) {
	for _, v := range processApplyVariants() {
		if v.name == name {
			return v.manifestYAML, v.useUserRole, v.expectedStatus, processCaseID("apply", v.name), true
		}
	}
	return "", false, 0, "", false
}

// InboxVariantByName returns the inbox variant fields for the given name, including
// its catalogue CaseID (see ProcessApplyManifest).
func InboxVariantByName(name string) (op string, crossTenant, wrongRole bool, caseID CaseID, found bool) {
	for _, cand := range inboxVariants() {
		if cand.name == name {
			return cand.op, cand.crossTenant, cand.wrongRole, processCaseID("inbox", cand.name), true
		}
	}
	return "", false, false, "", false
}

func renderProcessApplyFeature(seed int64, v processApplyVariant) string {
	roleClause := "lm_admin"
	if v.useUserRole {
		roleClause = "lm_user"
	}
	var thenLine string
	switch v.expectedStatus {
	case 400:
		thenLine = "    Then the process apply response status is 400"
	case 403:
		thenLine = "    Then the process apply response status is 403\n    And wrong-role-process-apply must not return 200"
	default:
		thenLine = "    Then the process apply response status is not 500"
	}
	return fmt.Sprintf(`Feature: process-apply %s [seed=%d]
  Scenario: %s
    Given a process manifest for variant %q [seed=%d]
    When I apply the process manifest as %s
%s
`, v.name, seed, v.feature, v.name, seed, roleClause, thenLine)
}

func renderInboxFeature(seed int64, v inboxVariant) string {
	roleClause := ""
	if v.wrongRole {
		roleClause = " as viewer"
	} else if v.crossTenant {
		roleClause = " as cross-tenant"
	}
	var thenLine string
	switch {
	case v.op == "read" && v.maxRows > 0:
		thenLine = fmt.Sprintf("    Then the inbox response status is 200\n    And the inbox response has at most %d rows", v.maxRows)
	case v.op == "read" && v.expectedStatus == 200:
		thenLine = "    Then the inbox response status is 200\n    And the inbox response is a safe array"
	case v.op == "read":
		thenLine = fmt.Sprintf("    Then the inbox response status is %d", v.expectedStatus)
	default: // ack
		thenLine = fmt.Sprintf("    Then the inbox ack response status is %d", v.expectedStatus)
	}
	opClause := "read the inbox"
	if v.op == "ack" {
		opClause = fmt.Sprintf("ack inbox id %q", v.query)
	} else if v.query != "" {
		opClause = fmt.Sprintf("read the inbox with query %q", v.query)
	}
	return fmt.Sprintf(`Feature: process-inbox %s [seed=%d]
  Scenario: %s
    Given the inbox variant %q [seed=%d]
    When I %s%s
%s
`, v.name, seed, v.feature, v.name, seed, opClause, roleClause, thenLine)
}
