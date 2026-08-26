//go:build otk

package otk

import (
	"fmt"
	"math"
	"os"
	"strconv"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
)

// goldMinScenarios is the floor for an honest Gold badge: "0 of any severity over
// N >= 500 scenarios". Below this, gold mode fails fast rather than claim Gold.
const goldMinScenarios = 500

// goldSeedsFromEnv parses OTK_GOLD_SEEDS, the per-lane seed count that enables
// gold mode. It returns (0, nil) when the variable is unset or empty (gold mode
// off; the per-PR corpus path runs). It returns an error when the value is
// present but invalid (non-numeric, overflow, or <= 0) so the caller fails fast
// rather than silently falling back to the Silver corpus path — that silent
// fallback would let an accidentally-bad value produce a false-green "gold" run.
func goldSeedsFromEnv() (int, error) {
	v, ok := os.LookupEnv("OTK_GOLD_SEEDS")
	if !ok || v == "" {
		return 0, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("OTK_GOLD_SEEDS=%q is invalid (want a positive integer); "+
			"unset it to run the Silver corpus path", v)
	}
	return n, nil
}

// enoughForGold reports whether n scenarios meet the Gold floor.
func enoughForGold(n int) bool { return n >= goldMinScenarios }

// goldLanes is the number of adversarial lanes generated per seed
// (apply/run/rest/auth/entity/process/view) — the multiplier from seeds to scenarios.
const goldLanes = 7

// expectedGoldScenarios returns goldLanes*m, or an error if m is non-positive or
// large enough that goldLanes*m would overflow int. Validating the count BEFORE
// goldFeatures lets the caller reject a misconfigured OTK_GOLD_SEEDS with a clear
// message instead of attempting a huge (or overflowing) allocation that crashes
// the test process.
func expectedGoldScenarios(m int) (int, error) {
	if m <= 0 {
		return 0, fmt.Errorf("gold seed count %d must be positive", m)
	}
	if m > math.MaxInt/goldLanes {
		return 0, fmt.Errorf("gold seed count %d is too large (goldLanes*m overflows int)", m)
	}
	return goldLanes * m, nil
}

// goldFeatures builds the in-memory Gold corpus: for each seed 1..m, one godog
// feature per adversarial lane -> goldLanes*m features. The smoke validate check
// is a per-PR concern and is intentionally excluded. Feature names mirror the
// committed corpus file prefixes (the apply lane is "seed_", matching
// seed_0001.feature) so logs correlate across the corpus and gold modes. Seeds
// map deterministically through the generator (splitmix64), so a gold run is
// reproducible and a failure names the exact seed.
func goldFeatures(m int) []godog.Feature {
	// No goldLanes*m preallocation: it would overflow int for a pathological m. The slice
	// grows naturally; callers validate the count first via expectedGoldScenarios.
	var feats []godog.Feature
	for s := int64(1); s <= int64(m); s++ {
		feats = append(feats,
			godog.Feature{Name: fmt.Sprintf("seed_%04d", s), Contents: []byte(generator.Generate(s).Feature)},
			godog.Feature{Name: fmt.Sprintf("run_%04d", s), Contents: []byte(generator.GenerateRun(s).Feature)},
			godog.Feature{Name: fmt.Sprintf("rest_%04d", s), Contents: []byte(generator.GenerateRest(s).Feature)},
			godog.Feature{Name: fmt.Sprintf("auth_%04d", s), Contents: []byte(generator.GenerateAuth(s).Feature)},
			godog.Feature{Name: fmt.Sprintf("entity_%04d", s), Contents: []byte(generator.GenerateEntity(s).Feature)},
			godog.Feature{Name: fmt.Sprintf("process_%04d", s), Contents: []byte(generator.GenerateProcess(s).Feature)},
			godog.Feature{Name: fmt.Sprintf("view_%04d", s), Contents: []byte(generator.GenerateView(s).Feature)},
		)
	}
	return feats
}
