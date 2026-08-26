package generator

import (
	"fmt"
	"strings"
	"testing"
)

func TestGenerate_IsDeterministicForSameSeed(t *testing.T) {
	a, b := Generate(7), Generate(7)
	if a.Feature != b.Feature || a.ManifestYAML != b.ManifestYAML ||
		a.Name != b.Name || a.Category != b.Category {
		t.Fatalf("same seed must produce an identical scenario")
	}
}

func TestGenerate_DiffersAcrossSeeds(t *testing.T) {
	// Features embed the seed, so they always differ (connector names may collide
	// when a name-targeting attack payload repeats — names are not unique keys).
	if Generate(1).Feature == Generate(2).Feature {
		t.Errorf("different seeds must yield different feature text")
	}
}

func TestGenerate_EmitsGherkinAndSeed(t *testing.T) {
	s := Generate(1)
	if !strings.Contains(s.Feature, "Feature:") || !strings.Contains(s.Feature, "Scenario:") {
		t.Errorf("feature is not Gherkin:\n%s", s.Feature)
	}
	if !strings.Contains(s.Feature, "seed=1") {
		t.Errorf("feature must record its seed for replay:\n%s", s.Feature)
	}
}

func TestGenerate_CategoryIsOneOfFour(t *testing.T) {
	valid := map[string]bool{"logical": true, "weird": true, "textual": true, "hack": true}
	if !valid[Generate(3).Category] {
		t.Errorf("category must be one of the four, got %q", Generate(3).Category)
	}
}

func TestGenerate_AllCategoriesReachable(t *testing.T) {
	seen := map[string]bool{}
	for s := int64(0); s <= 1000; s++ {
		seen[Generate(s).Category] = true
	}
	for _, c := range []string{"logical", "weird", "textual", "hack"} {
		if !seen[c] {
			t.Errorf("category %q never generated over seeds 0..1000", c)
		}
	}
}

// Every manifest must carry the hub connector envelope so lm validate reaches
// the csv-file schema validator instead of failing on envelope checks.
func TestGenerate_ManifestHasCorrectEnvelope(t *testing.T) {
	m := Generate(1).ManifestYAML
	for _, want := range []string{
		"apiVersion: connectors.lumnik.io/v1",
		"kind: Connector",
		"metadata:",
		"  connector_type: csv-file",
	} {
		if !strings.Contains(m, want) {
			t.Errorf("manifest missing envelope field %q:\n%s", want, m)
		}
	}
	// name and connector_type must NOT appear at document root (unindented)
	for _, line := range strings.Split(m, "\n") {
		if strings.HasPrefix(line, "name:") {
			t.Errorf("name: at document root — must be under metadata: %q", line)
		}
		if strings.HasPrefix(line, "connector_type:") {
			t.Errorf("connector_type: at document root — must be under metadata: %q", line)
		}
	}
}

func TestGenerate_ManifestIsRealCsvFileSchema(t *testing.T) {
	m := Generate(3).ManifestYAML
	for _, want := range []string{
		"apiVersion: connectors.lumnik.io/v1", "kind: Connector", "metadata:",
		"connector_type: csv-file", "spec:", "transport:", "parser:",
		"after_process:", "target:",
	} {
		if !strings.Contains(m, want) {
			t.Errorf("manifest missing %q:\n%s", want, m)
		}
	}
}

// The manifest's connector name MUST equal Scenario.Name (whatever it is, even a
// payload) so reports and any name-based reasoning stay consistent.
func TestGenerate_ManifestDeclaresScenarioName(t *testing.T) {
	s := Generate(7)
	want := fmt.Sprintf("name: %q", s.Name)
	if !strings.Contains(s.ManifestYAML, want) {
		t.Errorf("manifest must declare %s:\n%s", want, s.ManifestYAML)
	}
}

// empty-delimiter must render delimiter: "" in the manifest — an explicit empty
// string sent to the hub — not simply omit the field (which is valid per docs
// and defeats the adversarial intent of the variant).
func TestEmptyDelimiter_RendersExplicitEmpty(t *testing.T) {
	for seed := int64(1); seed <= 1000; seed++ {
		s := Generate(seed)
		if s.Variant == "empty-delimiter" {
			if !strings.Contains(s.ManifestYAML, "delimiter: \"\"") {
				t.Errorf("empty-delimiter: manifest must contain explicit delimiter: \"\", got:\n%s", s.ManifestYAML)
			}
			return
		}
	}
	t.Skip("empty-delimiter not reached in seeds 1..1000")
}

// move-without-path mutates spec.after_process (not spec.archive_path, which is
// already absent in the baseline). The Field label must reflect what changed.
func TestMoveWithoutPath_FieldIsAfterProcess(t *testing.T) {
	for seed := int64(1); seed <= 1000; seed++ {
		s := Generate(seed)
		if s.Variant == "move-without-path" {
			if s.Field != "spec.after_process" {
				t.Errorf("move-without-path: Field = %q, want %q", s.Field, "spec.after_process")
			}
			return
		}
	}
	t.Skip("move-without-path not reached in seeds 1..1000")
}

// Non-name attacks keep the stable trackable name; only name-targeting variants
// rename the connector.
func TestGenerate_NameStableUnlessNameTargeted(t *testing.T) {
	for s := int64(0); s <= 200; s++ {
		g := Generate(s)
		if g.Field == "name" {
			continue // name-targeting variant: rename is expected
		}
		stable := fmt.Sprintf("otk-adv-%d", s)
		if g.Name != stable {
			t.Errorf("seed %d: non-name attack must keep %q, got %q (field=%s)",
				s, stable, g.Name, g.Field)
		}
	}
}

func TestGenerateRest_IsDeterministicForSameSeed(t *testing.T) {
	a, b := GenerateRest(7), GenerateRest(7)
	if a.Feature != b.Feature || a.ManifestYAML != b.ManifestYAML ||
		a.Category != b.Category || a.Variant != b.Variant {
		t.Fatal("same seed must produce an identical REST scenario")
	}
}

func TestGenerateRest_DiffersAcrossSeeds(t *testing.T) {
	if GenerateRest(1).Feature == GenerateRest(2).Feature {
		t.Error("different seeds must yield different REST feature text")
	}
}

func TestGenerateRest_EmitsGherkinAndSeed(t *testing.T) {
	s := GenerateRest(1)
	if !strings.Contains(s.Feature, "Feature:") || !strings.Contains(s.Feature, "Scenario:") {
		t.Errorf("REST feature is not Gherkin:\n%s", s.Feature)
	}
	if !strings.Contains(s.Feature, "seed=1") {
		t.Errorf("REST feature must record its seed for replay:\n%s", s.Feature)
	}
}

func TestGenerateRest_CategoryIsOneOfFour(t *testing.T) {
	valid := map[string]bool{"logical": true, "weird": true, "textual": true, "hack": true}
	if !valid[GenerateRest(3).Category] {
		t.Errorf("REST category must be one of the four, got %q", GenerateRest(3).Category)
	}
}

func TestGenerateRest_AllCategoriesReachable(t *testing.T) {
	seen := map[string]bool{}
	for s := int64(0); s <= 1000; s++ {
		seen[GenerateRest(s).Category] = true
	}
	for _, c := range []string{"logical", "weird", "textual", "hack"} {
		if !seen[c] {
			t.Errorf("REST category %q never generated over seeds 0..1000", c)
		}
	}
}

func TestGenerateRest_ManifestHasRestEnvelope(t *testing.T) {
	m := GenerateRest(1).ManifestYAML
	for _, want := range []string{
		"apiVersion: connectors.lumnik.io/v1",
		"kind: Connector",
		"metadata:",
		"connector_type: rest-generic",
	} {
		if !strings.Contains(m, want) {
			t.Errorf("REST manifest missing envelope field %q:\n%s", want, m)
		}
	}
	for _, line := range strings.Split(m, "\n") {
		if strings.HasPrefix(line, "connector_type:") {
			t.Errorf("connector_type: at document root — must be under metadata: %q", line)
		}
	}
}

func TestGenerateRest_FeatureContainsRestKeyword(t *testing.T) {
	s := GenerateRest(5)
	if !strings.Contains(s.Feature, "rest-generic") {
		t.Errorf("REST feature must mention rest-generic:\n%s", s.Feature)
	}
}

func TestGenerateAuth_IsDeterministic(t *testing.T) {
	a := GenerateAuth(7)
	b := GenerateAuth(7)
	if a != b {
		t.Fatalf("GenerateAuth(7) not deterministic:\n%+v\n%+v", a, b)
	}
}

func TestGenerateAuth_PicksAKnownDefect(t *testing.T) {
	got := GenerateAuth(3).DefectName
	ok := false
	for _, d := range authDefects {
		if d == got {
			ok = true
		}
	}
	if !ok {
		t.Fatalf("GenerateAuth defect %q not in authDefects", got)
	}
}

func TestGenerateAuth_FeatureCarriesSeedAndDefect(t *testing.T) {
	sc := GenerateAuth(5)
	if !strings.Contains(sc.Feature, "seed=5") || !strings.Contains(sc.Feature, sc.DefectName) {
		t.Fatalf("auth feature must name the seed and defect:\n%s", sc.Feature)
	}
	if !strings.Contains(sc.Feature, "the hub rejects the adversarial token for seed 5") {
		t.Fatalf("auth feature must use the bound auth step:\n%s", sc.Feature)
	}
}

func TestAuthDefects_CoverTheMatrix(t *testing.T) {
	// All seven defects must appear across seeds 1..20 (guards the corpus range).
	seen := map[string]bool{}
	for seed := int64(1); seed <= 20; seed++ {
		seen[GenerateAuth(seed).DefectName] = true
	}
	for _, d := range authDefects {
		if !seen[d] {
			t.Fatalf("defect %q never appears in seeds 1..20; widen the range or selection", d)
		}
	}
}
