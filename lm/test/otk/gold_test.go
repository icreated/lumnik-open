//go:build otk

package otk

import (
	"math"
	"strings"
	"testing"
)

func TestExpectedGoldScenarios(t *testing.T) {
	if n, err := expectedGoldScenarios(100); err != nil || n != 100*goldLanes {
		t.Fatalf("expectedGoldScenarios(100) = (%d,%v), want (%d,nil)", n, err, 100*goldLanes)
	}
	if _, err := expectedGoldScenarios(0); err == nil {
		t.Fatal("m=0 must be an error")
	}
	if _, err := expectedGoldScenarios(-3); err == nil {
		t.Fatal("negative m must be an error")
	}
	// goldLanes*m would overflow int -> must error, not panic/wrap.
	if _, err := expectedGoldScenarios(math.MaxInt/goldLanes + 1); err == nil {
		t.Fatal("overflowing m must be an error")
	}
}

func TestGoldFeatures_countAndLanes(t *testing.T) {
	feats := goldFeatures(100)
	if len(feats) != 100*goldLanes {
		t.Fatalf("goldFeatures(100) = %d features, want %d (%d lanes x 100)", len(feats), 100*goldLanes, goldLanes)
	}
	if !enoughForGold(len(feats)) {
		t.Fatalf("%d features must satisfy the Gold floor", 100*goldLanes)
	}
}

func TestGoldFeatures_mappingAndUniqueness(t *testing.T) {
	feats := goldFeatures(2)
	if len(feats) != 2*goldLanes {
		t.Fatalf("goldFeatures(2) = %d, want %d (%d lanes x 2)", len(feats), 2*goldLanes, goldLanes)
	}
	names := map[string]bool{}
	lanes := map[string]int{}
	for _, f := range feats {
		if names[f.Name] {
			t.Fatalf("duplicate feature name %q", f.Name)
		}
		names[f.Name] = true
		idx := strings.IndexByte(f.Name, '_')
		if idx < 0 {
			t.Fatalf("feature name %q has no lane prefix (expected <lane>_NNNN)", f.Name)
		}
		lane := f.Name[:idx]
		lanes[lane]++
		if !strings.Contains(string(f.Contents), "seed=") {
			t.Fatalf("feature %q content missing seed marker: %q", f.Name, f.Contents)
		}
	}
	// Feature-name prefixes mirror the committed corpus: the apply lane is "seed_".
	for _, lane := range []string{"seed", "run", "rest", "auth", "entity", "process", "view"} {
		if lanes[lane] != 2 {
			t.Fatalf("lane %s has %d features, want 2", lane, lanes[lane])
		}
	}
	for _, want := range []string{"seed_0001", "seed_0002", "run_0001", "auth_0002", "entity_0001", "process_0001", "view_0001"} {
		if !names[want] {
			t.Fatalf("missing expected feature name %q", want)
		}
	}
}

func TestGoldSeedsFromEnv(t *testing.T) {
	// Empty value = gold mode off (0, nil).
	t.Setenv("OTK_GOLD_SEEDS", "")
	if n, err := goldSeedsFromEnv(); n != 0 || err != nil {
		t.Fatalf("empty OTK_GOLD_SEEDS -> (%d,%v), want (0,nil)", n, err)
	}
	// Valid positive integers -> (n, nil).
	for in, want := range map[string]int{"1": 1, "125": 125, "500": 500} {
		t.Setenv("OTK_GOLD_SEEDS", in)
		if n, err := goldSeedsFromEnv(); n != want || err != nil {
			t.Fatalf("OTK_GOLD_SEEDS=%q -> (%d,%v), want (%d,nil)", in, n, err, want)
		}
	}
	// Present-but-invalid (non-numeric, overflow, <=0) MUST be a hard error so the
	// run never silently falls back to Silver (honest-badge invariant).
	for _, in := range []string{"0", "-5", "abc", "12x", "9999999999999999999999"} {
		t.Setenv("OTK_GOLD_SEEDS", in)
		if n, err := goldSeedsFromEnv(); err == nil {
			t.Fatalf("OTK_GOLD_SEEDS=%q must be a hard error, got (%d,nil)", in, n)
		}
	}
}

func TestEnoughForGold(t *testing.T) {
	if enoughForGold(499) {
		t.Fatal("499 must not satisfy the Gold floor")
	}
	if !enoughForGold(500) {
		t.Fatal("500 must satisfy the Gold floor")
	}
}
