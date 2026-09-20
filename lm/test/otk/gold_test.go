//go:build otk

package otk

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/generator"
)

func TestCatalogueFeaturesEmitEveryCase(t *testing.T) {
	feats, err := catalogueFeatures()
	if err != nil {
		t.Fatalf("catalogueFeatures(): %v", err)
	}
	if len(feats) != len(generator.Catalogue()) {
		t.Fatalf("catalogueFeatures() emitted %d features, catalogue holds %d",
			len(feats), len(generator.Catalogue()))
	}
	names := map[string]bool{}
	for _, f := range feats {
		if names[f.Name] {
			t.Errorf("duplicate feature name %q", f.Name)
		}
		names[f.Name] = true
		if len(f.Contents) == 0 {
			t.Errorf("feature %q has empty contents", f.Name)
		}
		if !strings.Contains(string(f.Contents), "seed=") {
			t.Errorf("feature %q content missing the seed marker", f.Name)
		}
	}
}

func TestCatalogueFeaturesCoverEveryLane(t *testing.T) {
	feats, err := catalogueFeatures()
	if err != nil {
		t.Fatalf("catalogueFeatures(): %v", err)
	}
	lanes := map[string]int{}
	for _, f := range feats {
		lane := f.Name[:strings.Index(f.Name, "__")]
		lanes[lane]++
	}
	if want := len(generator.Lanes()); len(lanes) != want {
		t.Fatalf("features cover %d lanes, want %d: %v", len(lanes), want, lanes)
	}
	for _, l := range generator.Lanes() {
		if lanes[l.Name] == 0 {
			t.Errorf("lane %s emitted no feature", l.Name)
		}
	}
}

// The name has to carry the ID and the seed: the ID to read, the seed to replay.
func TestFeatureNameCarriesCaseAndSeed(t *testing.T) {
	got := featureNameFor(generator.Witness{ID: "apply/hack/sqli#0", Seed: 42})
	const want = "apply__hack__sqli__p0__seed0042"
	if got != want {
		t.Fatalf("featureNameFor = %q, want %q", got, want)
	}
	// A lane without a payload index keeps a clean name.
	got = featureNameFor(generator.Witness{ID: "auth/bad-audience", Seed: 7})
	if want := "auth__bad-audience__seed0007"; got != want {
		t.Fatalf("featureNameFor = %q, want %q", got, want)
	}
}
