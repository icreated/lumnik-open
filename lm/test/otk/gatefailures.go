package otk

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// gateFailures lists EVERY reason the gate must fail, so that no reason can hide another.
//
// The gate used to `t.Fatalf` on a non-zero godog status before the verdict was ever computed.
// One hard step error — a single run out of 875 scenarios failing to reach a terminal status —
// therefore discarded the whole graded corpus. Across the four red otk-gold nights of
// 2026-07-15 → 07-26, seven HIGH atomicity findings ("hub reported 0 row(s) written but the DB
// holds 1") were found and never once reported: the badge logic never saw them, and neither did
// anyone reading the bell's issue.
//
// A gate that hides what it found is worse than a gate that fails: the failure is visible, the
// silence is not.
func gateFailures(v oracle.Verdict, godogStatus int) []string {
	var reasons []string
	if !v.Passed {
		reasons = append(reasons, fmt.Sprintf(
			"OTK %s gate FAILED: %d finding(s) at or above the tier threshold", v.Tier, len(v.Findings)))
	}
	if godogStatus != 0 {
		reasons = append(reasons, fmt.Sprintf("godog reported failing steps (status %d)", godogStatus))
	}
	return reasons
}
