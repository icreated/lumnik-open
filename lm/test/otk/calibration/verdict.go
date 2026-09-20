package calibration

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// Phase is which half of the causal argument a run is making.
//
// Injected is the zero value because it is the default: an unset OTK_FAULT_PHASE
// runs the injected phase, and OTK_FAULT_PHASE=control asks for the control.
type Phase int

const (
	Injected Phase = iota
	Control
)

// ControlValue is the only value OTK_FAULT_PHASE accepts besides being unset.
//
// The phase lives in its own variable rather than inside OTK_FAULT because
// OTK_FAULT's grammar is user-facing and already settled: a name in the table, and
// nothing else. Prefixing it would make every reader of OTK_FAULT learn about a
// phase they never set — otkcal drives both phases and sets this itself.
const ControlValue = "control"

func (p Phase) String() string {
	if p == Control {
		return "CONTROL"
	}
	return "INJECTED"
}

// FloorPercent is how much of the catalogue must have been ATTEMPTED for a
// calibration verdict to mean anything.
const FloorPercent = 90

// MinAttempted is the floor in scenarios, rounded up: 90% of 136 is 122.4, and
// accepting 122 would be accepting less than the floor.
func MinAttempted(total int) int { return (total*FloorPercent + 99) / 100 }

// Judge returns nil when the phase's contract holds, and an error naming the phase
// otherwise.
//
// The gate INVERTS here; it does not switch off. In the injected phase the run
// passes if and only if a finding matches the declared family, severity and detail
// pattern — the ABSENCE of that finding is the failure, and that inversion is the
// entire point. In the control phase the same finding must be absent, because a
// finding already present on the branch (a genuine regression, a dirty hub, a
// leftover) would satisfy the injected phase without the injection having caused
// anything. Presence is correlation; causality needs the control.
//
// Judge never sees the godog status. The hub is deliberately broken, so steps WILL
// fail; treating a non-zero status as a verdict would make every calibration
// trivially successful. The status is logged and discarded by the caller.
func Judge(p Phase, f Fault, findings []oracle.Finding, attempted, total int) error {
	// The floor comes first, in BOTH phases. An injected phase that crashed early and
	// happened to emit the right finding is not proof; a control phase that ran
	// nothing observes the finding absent for free, which is worse.
	if total <= 0 {
		return fmt.Errorf("%s phase of fault %q: the catalogue is empty, so there is nothing to calibrate against", p, f.Name)
	}
	if attempted < MinAttempted(total) {
		return fmt.Errorf(
			"%s phase of fault %q FAILED: only %d of %d catalogue scenarios were ATTEMPTED "+
				"(the floor is %d%%, i.e. %d) — a battery that stopped early proves nothing, whatever it found",
			p, f.Name, attempted, total, FloorPercent, MinAttempted(total))
	}

	var matched []string
	for _, g := range findings {
		if f.Matches(g) {
			matched = append(matched, g.String())
		}
	}

	if p == Control {
		if len(matched) > 0 {
			return fmt.Errorf(
				"CONTROL phase of fault %q FAILED: the UNFAULTED hub already raises the declared finding, "+
					"so an injected run could not attribute it to the fault. This accuses the PRODUCT, not the "+
					"harness — the branch already exhibits what this fault imitates:\n  %s",
				f.Name, strings.Join(matched, "\n  "))
		}
		return nil
	}

	if len(matched) == 0 {
		return fmt.Errorf(
			"INJECTED phase of fault %q FAILED: the fault was applied and NO finding matched "+
				"family=%s severity>=%s detail~=%q, across %d finding(s) collected from %d attempted scenario(s). "+
				"This accuses the HARNESS: the hub was broken on purpose and the battery did not say so",
			f.Name, f.Family, f.MinSeverity, f.Pattern, len(findings), attempted)
	}
	return nil
}
