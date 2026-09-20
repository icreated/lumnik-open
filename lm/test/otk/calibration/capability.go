package calibration

import (
	"fmt"
	"strings"
)

// Liveness is one probe's answer.
//
// Probe and Remedy are carried even when Live is true so a refusal can name all
// three things a reader needs: the capability, the probe that answered, and what
// would satisfy it. A refusal that names only the capability leaves the reader to
// guess which probe failed and what to do about it.
type Liveness struct {
	Live   bool
	Probe  string // the probe that answered, in the words the harness already uses
	Remedy string // what would satisfy it
}

// Probe answers whether one capability is alive.
//
// A capability maps to the liveness probe the harness ALREADY runs, not to a list
// of variable names. DB truth is not "OTK_DB_DSN is set", it is "the snapshot probe
// returned live" — which also covers psql being absent or failing. The harness
// already computes exactly that and logs it as DORMANT; reusing it beats
// re-deriving a weaker version beside it.
type Probe func() Liveness

// CheckCapabilities refuses the run when any capability the fault declares is not
// alive, or has no probe at all.
//
// Not a skip, not a warning: several checks are dormant rather than absent when
// their environment is missing, and a dormant check never fires. The injected phase
// would then fail for a reason indistinguishable from "the check is blind", which
// is precisely the confusion this lot exists to remove.
func CheckCapabilities(f Fault, probes map[Capability]Probe) error {
	var refusals []string
	for _, c := range f.Capabilities {
		probe, ok := probes[c]
		if !ok {
			refusals = append(refusals, fmt.Sprintf(
				"%s: no probe is wired for this capability, so nothing could have checked it", c))
			continue
		}
		if l := probe(); !l.Live {
			refusals = append(refusals, fmt.Sprintf(
				"%s: NOT LIVE — probe %q answered no; to satisfy it: %s", c, l.Probe, l.Remedy))
		}
	}
	if len(refusals) == 0 {
		return nil
	}
	return fmt.Errorf(
		"fault %q cannot be calibrated here — %d declared capability/capabilities are not alive, "+
			"and a calibration that cannot observe its own finding must never run:\n  %s",
		f.Name, len(refusals), strings.Join(refusals, "\n  "))
}
