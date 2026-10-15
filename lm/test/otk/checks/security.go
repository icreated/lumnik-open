package checks

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// SecretEcho checks invariant family 3 (Go-side): a high-entropy sentinel injected
// via an env var (referenced by the manifest) must never appear in lm output. The
// sentinel is high-entropy, so there is no false-positive path. Empty sentinel
// never fires.
func SecretEcho(output, sentinel string, caseID generator.CaseID, seed int64) []oracle.Finding {
	if sentinel != "" && strings.Contains(output, sentinel) {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: "injected secret sentinel echoed in lm output",
		}}
	}
	return nil
}

// FileDisclosure checks invariant family 3 (Go-side): a path-traversal payload
// must never cause lm to leak local file CONTENTS. We look for the /etc/passwd
// signature; echoing the path itself in a rejection message is fine.
func FileDisclosure(output string, caseID generator.CaseID, seed int64) []oracle.Finding {
	if strings.Contains(output, "root:x:0:0") {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: "local file contents disclosed in lm output (/etc/passwd signature)",
		}}
	}
	return nil
}

// RLSIsolation checks invariant family 3 (DB-side): under a database session
// pinned to one tenant, a row belonging to a DIFFERENT tenant must never be
// visible. This is the direct Postgres row-level-security analog of
// CrossTenantLeak's HTTP-level probe (family Auth): CrossTenantLeak asks whether
// the API rendered a foreign row; RLSIsolation asks whether the POLICY itself would
// have admitted it — a signal that survives even if some future change made the
// API layer blind to what the database is doing. A regression can therefore tell
// "the API's access control broke" apart from "the RLS policy itself broke", even
// though both would leak the same row.
//
// foreignRowsVisible is a count, not a bool: any positive number is one breach,
// counted once — the SEVERITY of an isolation crossing does not scale with how many
// rows happened to be present when it was observed.
func RLSIsolation(foreignRowsVisible int, caseID generator.CaseID, seed int64) []oracle.Finding {
	if foreignRowsVisible > 0 {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: fmt.Sprintf(
				"RLS isolation crossed: %d foreign-tenant row(s) visible under a different tenant's DB session",
				foreignRowsVisible),
		}}
	}
	return nil
}
