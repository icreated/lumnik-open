package oracle

import "fmt"

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
	Seed     int64
	Detail   string
}

func (f Finding) String() string {
	return fmt.Sprintf("[%s] %s (seed=%d): %s", f.Severity, f.Family, f.Seed, f.Detail)
}
