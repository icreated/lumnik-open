//go:build otk

package otk

import (
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// BatterySummary is what "the battery ran" actually means, printed once at the
// end: what was exercised, what was dormant and why, and what it found. A green
// with seven dormant oracle families looks exactly like a green with none until
// this exists — see #588.
type BatterySummary struct {
	CasesAttempted int
	CasesDormant   int // dormant AND attempted — see buildSummary; a battery-level probe like hub-intactness is not a case and is excluded here, though it still appears in Dormancy
	CasesExercised int // CasesAttempted - CasesDormant, by construction (see buildSummary)
	Findings       map[oracle.Severity]int
	Dormancy       map[string][]dormancyEntry // lane -> every recorded reason, unfiltered — includes battery-level probes CasesDormant excludes
	Elapsed        time.Duration
}

// knownDormancyLanes is the closed set a case's lane must belong to: every
// generator.Lanes() entry (the Gold catalogue), plus the two surfaces that
// legitimately sit outside it — view-store (a committed-corpus smoke, never a
// Gold lane; see caseid.go) and hub-intactness (a once-per-battery probe, not a
// godog scenario at all; see otk_test.go). A lane outside this set — a typo, or a
// new lane added without updating this list — fails buildSummary loudly instead
// of quietly counting as "exercised".
func knownDormancyLanes() map[string]bool {
	known := map[string]bool{"view-store": true, "hub-intactness": true}
	for _, l := range generator.Lanes() {
		known[l.Name] = true
	}
	return known
}

// buildSummary is pure: every input is data the After hook already collected, so
// it is testable without a live hub. It refuses an empty CaseID or an unrecognized
// lane anywhere in attempted, dormant or findings — the actual mechanism behind
// "a family that goes quiet for a new reason cannot pass as exercised".
func buildSummary(attempted []generator.CaseID, dormant []dormancyEntry, findings []oracle.Finding, elapsed time.Duration) (BatterySummary, error) {
	known := knownDormancyLanes()

	attemptedSet := make(map[generator.CaseID]bool, len(attempted))
	for _, id := range attempted {
		if err := checkCaseID(known, id, "attempted"); err != nil {
			return BatterySummary{}, err
		}
		attemptedSet[id] = true
	}

	dormantSet := make(map[generator.CaseID]bool, len(dormant))
	byLane := make(map[string][]dormancyEntry)
	for _, d := range dormant {
		if err := checkCaseID(known, d.CaseID, "dormant"); err != nil {
			return BatterySummary{}, err
		}
		dormantSet[d.CaseID] = true
		lane := d.CaseID.Lane()
		byLane[lane] = append(byLane[lane], d)
	}

	bySeverity := make(map[oracle.Severity]int)
	for _, f := range findings {
		if err := checkCaseID(known, f.CaseID, "finding"); err != nil {
			return BatterySummary{}, err
		}
		bySeverity[f.Severity]++
	}

	// CasesDormant counts only dormancy tied to a case that was actually attempted —
	// a battery-level probe (hub-intactness) is recorded in dormantSet/byLane for
	// visibility, but was never a godog scenario, so it must never be added to (or
	// subtracted from) the case tally: CasesAttempted == CasesExercised +
	// CasesDormant holds by construction, both derived from the same intersection.
	casesDormant := 0
	for id := range dormantSet {
		if attemptedSet[id] {
			casesDormant++
		}
	}
	exercised := len(attemptedSet) - casesDormant

	return BatterySummary{
		CasesAttempted: len(attemptedSet),
		CasesDormant:   casesDormant,
		CasesExercised: exercised,
		Findings:       bySeverity,
		Dormancy:       byLane,
		Elapsed:        elapsed,
	}, nil
}

// checkCaseID rejects an empty CaseID and a lane outside the known set, naming
// which of attempted/dormant/finding it came from.
func checkCaseID(known map[string]bool, id generator.CaseID, from string) error {
	if id == "" {
		return fmt.Errorf("buildSummary: %s case has an empty CaseID", from)
	}
	if lane := id.Lane(); !known[lane] {
		return fmt.Errorf("buildSummary: %s case %q has an unknown lane %q", from, id, lane)
	}
	return nil
}

func (s BatterySummary) String() string {
	var b strings.Builder
	fmt.Fprintf(&b, "OTK battery summary: %d case(s) attempted, %d exercised, %d dormant, elapsed %s\n",
		s.CasesAttempted, s.CasesExercised, s.CasesDormant, s.Elapsed)

	if len(s.Dormancy) > 0 {
		lanes := make([]string, 0, len(s.Dormancy))
		for lane := range s.Dormancy {
			lanes = append(lanes, lane)
		}
		sort.Strings(lanes)
		for _, lane := range lanes {
			fmt.Fprintf(&b, "  dormant: %s\n", lane)
			for _, d := range s.Dormancy[lane] {
				fmt.Fprintf(&b, "    %s: %s\n", d.CaseID, d.Reason)
			}
		}
	}

	for _, sev := range []oracle.Severity{oracle.Critical, oracle.High, oracle.Medium, oracle.Low} {
		if n := s.Findings[sev]; n > 0 {
			fmt.Fprintf(&b, "  findings: %d %s\n", n, sev)
		}
	}
	return b.String()
}
