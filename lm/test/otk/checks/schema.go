package checks

import (
	"fmt"
	"sort"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// SchemaSnapshot is a read-only fingerprint of the hub DB taken via the non-owner
// role: the set of tables in tenant-visible schemas plus the connector.connector
// row count. Captured before apply and after teardown, per scenario.
type SchemaSnapshot struct {
	Tables        []string
	ConnectorRows int
}

// SchemaDiff checks invariant family 3 (hub-side intactness): a connector apply
// followed by teardown must leave the hub's table set and connector row count
// exactly as they were. Any difference is CRITICAL — SQLi DDL or a row that
// survived teardown.
func SchemaDiff(before, after SchemaSnapshot, caseID generator.CaseID, seed int64) []oracle.Finding {
	b, a := toSet(before.Tables), toSet(after.Tables)
	var added, dropped []string
	for t := range a {
		if !b[t] {
			added = append(added, t)
		}
	}
	for t := range b {
		if !a[t] {
			dropped = append(dropped, t)
		}
	}
	sort.Strings(added)
	sort.Strings(dropped)

	var out []oracle.Finding
	for _, t := range added {
		out = append(out, oracle.Finding{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: "table created during apply (possible SQLi DDL): " + t,
		})
	}
	for _, t := range dropped {
		out = append(out, oracle.Finding{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: "table dropped during apply (possible SQLi DDL): " + t,
		})
	}
	if after.ConnectorRows != before.ConnectorRows {
		out = append(out, oracle.Finding{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: fmt.Sprintf("connector.connector row count changed during apply: %d -> %d",
				before.ConnectorRows, after.ConnectorRows),
		})
	}
	return out
}

func toSet(xs []string) map[string]bool {
	m := make(map[string]bool, len(xs))
	for _, x := range xs {
		m[x] = true
	}
	return m
}
