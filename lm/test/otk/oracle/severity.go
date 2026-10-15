package oracle

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
)

// Severity grades the consequence of a scenario, SIEM-style.
type Severity int

const (
	None Severity = iota
	Low
	Medium
	High
	Critical
)

func (s Severity) String() string {
	switch s {
	case Critical:
		return "CRITICAL"
	case High:
		return "HIGH"
	case Medium:
		return "MEDIUM"
	case Low:
		return "LOW"
	default:
		return "NONE"
	}
}

// Family is the invariant that was violated.
type Family string

const (
	Robustness Family = "robustness"
	Atomicity  Family = "atomicity"
	Security   Family = "security"
	Text       Family = "text"
	Auth       Family = "auth"
)

// Finding is one invariant violation produced by an oracle check.
type Finding struct {
	Severity Severity
	Family   Family
	CaseID   generator.CaseID // the catalogue case that produced this finding; see generator.CaseID
	Seed     int64            // the witness seed for replay; only meaningful alongside CaseID
	Detail   string
}

func (f Finding) String() string {
	return fmt.Sprintf("[%s] %s case=%s (seed=%d): %s", f.Severity, f.Family, f.CaseID, f.Seed, f.Detail)
}

// Validate reports an error when a Finding is missing its case identity. Every
// Finding must name the case that produced it — a red run naming only a seed sends
// the reader back to re-run the generator to learn what was even attacked.
func (f Finding) Validate() error {
	if f.CaseID == "" {
		return fmt.Errorf("finding has no CaseID: %s", f)
	}
	return nil
}
