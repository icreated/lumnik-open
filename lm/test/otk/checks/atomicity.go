package checks

import (
	"encoding/json"
	"fmt"
	"sort"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// ConnectorNames parses `lm connector list -o json` into a name set.
// The parse error is returned to the caller, which decides how to treat it
// (a precondition fails hard, a probe emits a finding).
func ConnectorNames(listJSON string) (map[string]bool, error) {
	var items []struct {
		Name string `json:"name"`
	}
	if err := json.Unmarshal([]byte(listJSON), &items); err != nil {
		return nil, err
	}
	set := make(map[string]bool, len(items))
	for _, it := range items {
		set[it.Name] = true
	}
	return set, nil
}

// AtomicitySetDiff checks invariant family 2 via a before/after differential:
// any connector that appears in `after` but not `before` was born from a
// non-mutating validate call — a HIGH atomicity violation. Name-agnostic, so it
// works even when an attack payload IS the connector name, and it never flags a
// dirty-hub leftover (present in both snapshots).
func AtomicitySetDiff(before, after map[string]bool, seed int64) []oracle.Finding {
	var newNames []string
	for name := range after {
		if !before[name] {
			newNames = append(newNames, name)
		}
	}
	sort.Strings(newNames) // stable report order
	var out []oracle.Finding
	for _, name := range newNames {
		out = append(out, oracle.Finding{
			Severity: oracle.High, Family: oracle.Atomicity, Seed: seed,
			Detail: "connector born from a non-mutating validate call: " + name,
		})
	}
	return out
}

// AtomicityApply checks invariant family 2 for the mutating apply surface — two-sided
// and name-agnostic. The hub may sanitise metadata.name on persist, so we count the
// diff cardinality rather than predict a name:
//   - exit 0: exactly one connector must be created (0 = did not persist; >=2 = over-created)
//   - exit != 0: nothing must be created (a rejected apply must leak no state)
//
// Every deviation is a HIGH atomicity finding.
func AtomicityApply(before, after map[string]bool, applyExit int, seed int64) []oracle.Finding {
	created := 0
	for name := range after {
		if !before[name] {
			created++
		}
	}
	var out []oracle.Finding
	if applyExit == 0 {
		switch {
		case created == 0:
			out = append(out, oracle.Finding{
				Severity: oracle.High, Family: oracle.Atomicity, Seed: seed,
				Detail: "apply reported success (exit 0) but created no connector",
			})
		case created > 1:
			out = append(out, oracle.Finding{
				Severity: oracle.High, Family: oracle.Atomicity, Seed: seed,
				Detail: fmt.Sprintf("apply created %d connectors from a single manifest", created),
			})
		}
	} else if created > 0 {
		out = append(out, oracle.Finding{
			Severity: oracle.High, Family: oracle.Atomicity, Seed: seed,
			Detail: fmt.Sprintf("apply rejected (exit %d) but leaked %d connector(s)", applyExit, created),
		})
	}
	return out
}
