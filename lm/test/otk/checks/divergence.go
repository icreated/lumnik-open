package checks

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// Divergence is the security signal the mutating apply surface unlocks: the API
// view (lm connector list, RLS-scoped) and the DB truth (psql connector.connector
// rowcount, non-owner role) must agree on how many connectors apply created.
// Measured after apply, before teardown. A mismatch is CRITICAL — either a write
// that bypassed the API's visibility (apiDelta < dbDelta, an RLS-crossing / hidden
// write) or a phantom API claim (apiDelta > dbDelta).
func Divergence(apiDelta, dbDelta int, caseID generator.CaseID, seed int64) []oracle.Finding {
	if apiDelta != dbDelta {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: fmt.Sprintf("API/DB divergence on apply: API reported %d new connector(s), DB gained %d row(s)",
				apiDelta, dbDelta),
		}}
	}
	return nil
}

// RunTerminalDivergence is Divergence's sibling for the run-polling lane: DB truth (the
// connector_run row) and the API view (lm run get, polled to its deadline) must agree on
// whether a run has reached a terminal status. dbTerminal is DB truth read AFTER the poll loop
// already gave up — a run genuinely still in flight is thenRunTerminal's existing hard error,
// not this. A run terminal in DB truth that the API never reported within the poll deadline is
// the observable shape the July 2026 otk-gold flakiness diary
// (docs/studies/2026-07-27-otk-gold-flakiness-carnet.md) left unresolved: the DIVERGENCE is
// established, its mechanism is not, and this check grades only the former.
func RunTerminalDivergence(dbTerminal bool, caseID generator.CaseID, seed int64) []oracle.Finding {
	if dbTerminal {
		return []oracle.Finding{{
			Severity: oracle.Critical, Family: oracle.Security, CaseID: caseID, Seed: seed,
			Detail: "run terminal in DB truth but the API never reported it within the poll deadline",
		}}
	}
	return nil
}
