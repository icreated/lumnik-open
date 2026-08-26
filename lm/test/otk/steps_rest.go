//go:build otk

package otk

import (
	"fmt"
	"os"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
)

const stepRestManifest = `^the generated REST manifest for seed (\d+)$`

func init() {
	stepPatterns = append(stepPatterns, stepRestManifest)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepRestManifest, func(seed int) error { return st.givenRestManifestForSeed(int64(seed)) })
	})
}

func (s *scenarioState) givenRestManifestForSeed(seed int64) error {
	s.scen = generator.GenerateRest(seed)
	f, err := os.CreateTemp("", fmt.Sprintf("otk-rest-%d-*.yaml", seed))
	if err != nil {
		return err
	}
	s.manifest = f.Name()
	if _, werr := f.WriteString(s.scen.ManifestYAML); werr != nil {
		_ = f.Close()
		return werr
	}
	return f.Close()
}
