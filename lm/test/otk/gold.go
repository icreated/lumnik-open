//go:build otk

package otk

import (
	"fmt"
	"strings"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
)

// catalogueFeatures builds the Gold corpus: one godog feature per catalogue case,
// rendered from that case's witness seed.
//
// This replaced a seed-volume corpus (7 features per seed, N seeds) whose green
// meant "N draws passed" and nothing more: at 125 seeds, 875 executions reached 129
// of 136 cases — 85% repetition, and seven entries never run at all. One seed draws
// all seven lanes at once, so no seed count can fix that; the addressing had to change.
//
// The smoke validate check is a per-PR concern and stays out. Feature names carry the
// CaseID and its witness seed — the ID to read, the seed to replay.
func catalogueFeatures() ([]godog.Feature, error) {
	ws, err := generator.Witnesses()
	if err != nil {
		return nil, fmt.Errorf("gold corpus: %w", err)
	}
	feats := make([]godog.Feature, 0, len(ws))
	for _, w := range ws {
		contents, err := featureContentsFor(w)
		if err != nil {
			return nil, err
		}
		feats = append(feats, godog.Feature{Name: featureNameFor(w), Contents: []byte(contents)})
	}
	return feats, nil
}

// featureContentsFor renders the Gherkin for one witness through its lane's own
// generator, so a catalogue feature is byte-identical to what that seed always
// produced. The steps resolve the scenario from the seed embedded in the text;
// nothing about the step grammar changes.
func featureContentsFor(w generator.Witness) (string, error) {
	lane, ok := generator.LaneByName(w.ID.Lane())
	if !ok {
		return "", fmt.Errorf("gold corpus: witness %q has unknown lane %q", w.ID, w.ID.Lane())
	}
	return lane.Feature(w.Seed), nil
}

// featureNameFor turns a witness into a godog feature name:
//
//	apply/hack/sqli#0  + seed 42 -> apply__hack__sqli__p0__seed0042
//	auth/bad-audience  + seed  7 -> auth__bad-audience__seed0007
//
// Unique because CaseIDs are, and readable in a progress log without a lookup.
func featureNameFor(w generator.Witness) string {
	name := strings.ReplaceAll(string(w.ID), "/", "__")
	name = strings.ReplaceAll(name, "#", "__p")
	return fmt.Sprintf("%s__seed%04d", name, w.Seed)
}
