package checks

import (
	"strings"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

// Robustness checks invariant family 1: lm never crashes/hangs, and every
// error is a clean typed error — never a raw stack trace to the user.
func Robustness(r runner.Result, caseID generator.CaseID, seed int64) []oracle.Finding {
	switch {
	case r.Panicked:
		return []oracle.Finding{{
			Severity: oracle.High, Family: oracle.Robustness, CaseID: caseID, Seed: seed,
			Detail: "lm panicked / unhandled stack trace",
		}}
	case r.TimedOut:
		return []oracle.Finding{{
			Severity: oracle.High, Family: oracle.Robustness, CaseID: caseID, Seed: seed,
			Detail: "lm did not return within the timeout (hang)",
		}}
	case leakedServerStack(r.Stderr) || leakedServerStack(r.Stdout):
		return []oracle.Finding{{
			Severity: oracle.Medium, Family: oracle.Robustness, CaseID: caseID, Seed: seed,
			Detail: "server stack trace leaked to client output",
		}}
	}
	return nil
}

func leakedServerStack(s string) bool {
	return strings.Contains(s, "java.lang.") ||
		strings.Contains(s, "\tat io.lumnik") ||
		strings.Contains(s, "Exception in thread")
}
