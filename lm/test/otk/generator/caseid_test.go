package generator

import (
	"strings"
	"testing"
)

// The ID shape is per-lane on purpose: the catalogues are not uniform. run has no
// payload index (sourceVariant.build yields one content), auth is a flat list, entity
// and process are sub-typed, view is bare.
func TestCaseIDShapePerLane(t *testing.T) {
	cases := []struct {
		lane   string
		got    CaseID
		prefix string
		parts  int // number of "/"-separated segments
	}{
		{"apply", Generate(1).CaseID, "apply/", 3},
		{"run", GenerateRun(1).CaseID, "run/", 3},
		{"rest", GenerateRest(1).CaseID, "rest/", 3},
		{"auth", GenerateAuth(1).CaseID, "auth/", 2},
		{"entity", GenerateEntity(1).CaseID, "entity/", 3},
		{"process", GenerateProcess(1).CaseID, "process/", 3},
		{"view", GenerateView(1).CaseID, "view/", 2},
	}
	for _, c := range cases {
		s := string(c.got)
		if !strings.HasPrefix(s, c.prefix) {
			t.Errorf("%s: CaseID %q does not start with %q", c.lane, s, c.prefix)
		}
		if n := len(strings.Split(s, "/")); n != c.parts {
			t.Errorf("%s: CaseID %q has %d segments, want %d", c.lane, s, n, c.parts)
		}
	}
	// Only apply and rest carry a payload index.
	for _, id := range []CaseID{Generate(1).CaseID, GenerateRest(1).CaseID} {
		if !strings.Contains(string(id), "#") {
			t.Errorf("CaseID %q must carry a payload index", id)
		}
	}
	for _, id := range []CaseID{GenerateRun(1).CaseID, GenerateAuth(1).CaseID,
		GenerateEntity(1).CaseID, GenerateProcess(1).CaseID, GenerateView(1).CaseID} {
		if strings.Contains(string(id), "#") {
			t.Errorf("CaseID %q must NOT carry a payload index: that lane has none", id)
		}
	}
}

// Determinism within a build. Cross-version stability is deliberately NOT promised
// (see the spec, section 2): the seed is the replay address, bounded by the
// catalogue fingerprint (CatalogueFingerprint).
func TestCaseIDIsDeterministic(t *testing.T) {
	for s := int64(1); s <= 50; s++ {
		if a, b := Generate(s).CaseID, Generate(s).CaseID; a != b {
			t.Fatalf("seed %d: apply CaseID not deterministic: %q vs %q", s, a, b)
		}
		if a, b := GenerateEntity(s).CaseID, GenerateEntity(s).CaseID; a != b {
			t.Fatalf("seed %d: entity CaseID not deterministic: %q vs %q", s, a, b)
		}
	}
}

// An entity read scenario always carries its variant, in the field and in the ID.
func TestEntityReadScenarioCarriesItsVariant(t *testing.T) {
	var sawRead bool
	for s := int64(1); s <= 200; s++ {
		e := GenerateEntity(s)
		if e.SubType != "read" {
			continue
		}
		sawRead = true
		if e.Variant == "" {
			t.Fatalf("seed %d: entity read scenario has an empty Variant", s)
		}
		if want := CaseID("entity/read/" + e.Variant); e.CaseID != want {
			t.Fatalf("seed %d: CaseID = %q, want %q", s, e.CaseID, want)
		}
	}
	if !sawRead {
		t.Fatal("no entity read scenario in seeds 1..200 — the probe proves nothing")
	}
}
