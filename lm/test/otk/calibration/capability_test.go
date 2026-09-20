package calibration

import (
	"strings"
	"testing"
)

func alive(name string) Probe {
	return func() Liveness { return Liveness{Live: true, Probe: name} }
}

// A calibration that cannot possibly observe its own finding must never run: its
// red would be a lie about the harness, indistinguishable from "the check is
// blind". So this is a REFUSAL, not a skip and not a warning.
func TestFaultRefusesADormantCapability(t *testing.T) {
	f := Fault{Name: "f", Capabilities: []Capability{RequiresDBTruth}}
	probes := map[Capability]Probe{
		RequiresDBTruth: func() Liveness {
			return Liveness{
				Live:   false,
				Probe:  "hubSnapshot (psql read via OTK_DB_DSN)",
				Remedy: "set OTK_DB_DSN to the non-owner app-role DSN and put psql on PATH",
			}
		},
	}

	err := CheckCapabilities(f, probes)
	if err != nil {
		// Asserted on each of the three SEPARATELY: a refusal that names only the
		// capability leaves the reader to guess which probe failed and what to do
		// about it, and that guess is the cost this whole mechanism exists to remove.
		for _, want := range []string{
			"RequiresDBTruth",
			"hubSnapshot (psql read via OTK_DB_DSN)",
			"set OTK_DB_DSN to the non-owner app-role DSN and put psql on PATH",
		} {
			if !strings.Contains(err.Error(), want) {
				t.Errorf("the refusal must name %q, got: %v", want, err)
			}
		}
	} else {
		t.Fatal("a dormant declared capability must refuse the run")
	}
}

func TestCheckCapabilitiesPassesWhenAllAlive(t *testing.T) {
	f := Fault{Name: "f", Capabilities: []Capability{RequiresDBTruth, RequiresRealAuth}}
	probes := map[Capability]Probe{
		RequiresDBTruth:  alive("db"),
		RequiresRealAuth: alive("auth"),
	}
	if err := CheckCapabilities(f, probes); err != nil {
		t.Fatalf("all probes live must start the run, got: %v", err)
	}
}

// A capability with no probe would refuse nothing while looking like a guard.
func TestCheckCapabilitiesRefusesAnUnprobedCapability(t *testing.T) {
	f := Fault{Name: "f", Capabilities: []Capability{RequiresAdminDB}}
	err := CheckCapabilities(f, map[Capability]Probe{RequiresDBTruth: alive("db")})
	if err == nil {
		t.Fatal("a declared capability with no probe must refuse the run, not pass it")
	}
	if !strings.Contains(err.Error(), "RequiresAdminDB") || !strings.Contains(err.Error(), "no probe") {
		t.Errorf("the refusal must name the unprobed capability, got: %v", err)
	}
}

// Every dormant capability is reported, so a second one is not hidden by the first.
func TestCheckCapabilitiesReportsEveryDormantOne(t *testing.T) {
	dead := func(n string) Probe {
		return func() Liveness { return Liveness{Probe: n, Remedy: "remedy-" + n} }
	}
	f := Fault{Name: "f", Capabilities: []Capability{RequiresDBTruth, RequiresAdminDB}}
	err := CheckCapabilities(f, map[Capability]Probe{
		RequiresDBTruth: dead("probe-db"),
		RequiresAdminDB: dead("probe-admin"),
	})
	if err == nil {
		t.Fatal("two dormant capabilities must refuse the run")
	}
	if !strings.Contains(err.Error(), "probe-db") || !strings.Contains(err.Error(), "probe-admin") {
		t.Errorf("both dormant capabilities must be reported, got: %v", err)
	}
}
