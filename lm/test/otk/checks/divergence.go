package checks

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// Divergence is the security signal the mutating apply surface unlocks: the API
// view (lm connector list, RLS-scoped) and the DB truth (psql connector.connector
// rowcount, non-owner role) must agree on how many connectors apply created.
// Measured after apply, before teardown. A mismatch is CRITICAL — either a write
// that bypassed the API's visibility (apiDelta < dbDelta, an RLS-crossing / hidden
// write) or a phantom API claim (apiDelta > dbDelta).
func Divergence(apiDelta, dbDelta int, seed int64) []oracle.Finding {
	if apiDelta != dbDelta {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, Seed: seed,
			Detail: fmt.Sprintf("API/DB divergence on apply: API reported %d new connector(s), DB gained %d row(s)",
				apiDelta, dbDelta),
		}}
	}
	return nil
}
