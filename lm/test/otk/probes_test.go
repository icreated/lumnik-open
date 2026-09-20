//go:build otk

package otk

import (
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/calibration"
)

// A capability with no probe would refuse nothing while looking like a guard; a
// probe no capability reaches is dead weight that reads as coverage. Both sides.
func TestCapabilityProbeInventory(t *testing.T) {
	probes := calibrationProbes()

	var missing []calibration.Capability
	declared := map[calibration.Capability]bool{}
	for _, c := range calibration.Capabilities() {
		declared[c] = true
		if _, ok := probes[c]; !ok {
			missing = append(missing, c)
		}
	}
	var unexpected []calibration.Capability
	for c := range probes {
		if !declared[c] {
			unexpected = append(unexpected, c)
		}
	}
	if len(missing) > 0 || len(unexpected) > 0 {
		t.Fatalf("the capability set and the probe map disagree:\n  capabilities with no probe: %v\n  probes for no capability: %v",
			missing, unexpected)
	}
}

// Every probe must answer NOT LIVE in an empty environment, and must name itself and
// a remedy when it does — the refusal quotes all three, and a probe that answers "no"
// without saying which probe or what to do is the guess this mechanism exists to
// remove.
//
// The environment is cleared EXPLICITLY. Without that this test is not hub-free: an
// ambient OTK_DB_DSN would have it shell out to psql and reach a real database, an
// ambient OTK_KEY_FILE would have it read a key off disk, and `make otk-unit` would
// pass or fail depending on the developer's shell. Clearing it also pins the branch
// that actually matters, since the live branch is the one nobody's refusal quotes.
func TestEveryProbeAnswersAndNamesItself(t *testing.T) {
	clearEnv(t, "OTK_DB_DSN", "OTK_DB_ADMIN_DSN", "OTK_OIDC_ISSUER", "OTK_KEY_FILE", "OTK_OIDC_AUDIENCE")

	for c, p := range calibrationProbes() {
		t.Run(string(c), func(t *testing.T) {
			l := p()
			if l.Live {
				t.Fatalf("probe for %s reported LIVE with an empty environment — it is reading "+
					"something this test did not clear, and is therefore not hub-free", c)
			}
			if l.Probe == "" {
				t.Errorf("probe for %s does not name itself", c)
			}
			if l.Remedy == "" {
				t.Errorf("probe for %s answered NOT LIVE without saying what would satisfy it", c)
			}
		})
	}
}
