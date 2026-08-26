package generator

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// featureFor returns the expected Feature text for a committed corpus file,
// dispatching on the filename family prefix. ok=false marks hand-written files
// (e.g. smoke_*) that the golden lock must skip.
func featureFor(base string) (text string, ok bool) {
	var seed int64
	if _, err := fmt.Sscanf(base, "seed_%d.feature", &seed); err == nil {
		return Generate(seed).Feature, true
	}
	if _, err := fmt.Sscanf(base, "run_%d.feature", &seed); err == nil {
		return GenerateRun(seed).Feature, true
	}
	if _, err := fmt.Sscanf(base, "rest_%d.feature", &seed); err == nil {
		return GenerateRest(seed).Feature, true
	}
	if _, err := fmt.Sscanf(base, "auth_%d.feature", &seed); err == nil {
		return GenerateAuth(seed).Feature, true
	}
	if _, err := fmt.Sscanf(base, "entity_%d.feature", &seed); err == nil {
		return GenerateEntity(seed).Feature, true
	}
	return "", false
}

// TestCorpusMatchesGenerator is the golden lock: every committed corpus feature
// must be byte-identical to a fresh generation for its seed. A drift means the
// generator changed — regenerate deliberately with OTK_REGEN=1.
func TestCorpusMatchesGenerator(t *testing.T) {
	files, err := filepath.Glob("../corpus/*.feature")
	if err != nil || len(files) == 0 {
		t.Fatalf("no corpus files found (run OTK_REGEN=1 ...): %v", err)
	}
	checked := 0
	for _, f := range files {
		want, ok := featureFor(filepath.Base(f))
		if !ok {
			continue // hand-written feature (e.g. smoke_validate.feature)
		}
		got, err := os.ReadFile(f)
		if err != nil {
			t.Fatalf("read %s: %v", f, err)
		}
		if string(got) != want {
			t.Errorf("corpus drift in %s: regenerate with OTK_REGEN=1", filepath.Base(f))
		}
		checked++
	}
	if checked == 0 {
		t.Fatal("golden lock checked no generated corpus files")
	}
}

// TestRestCorpusCoversAllCategories guards that the committed REST corpus
// exercises all four input categories. Run OTK_REGEN=1 first if this fails.
func TestRestCorpusCoversAllCategories(t *testing.T) {
	files, err := filepath.Glob("../corpus/rest_*.feature")
	if err != nil {
		t.Fatalf("glob failed: %v", err)
	}
	if len(files) == 0 {
		t.Skip("no REST corpus files; run OTK_REGEN=1 go test -run TestRegenerateRestCorpus first")
	}
	seen := map[string]bool{}
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatalf("read %s: %v", filepath.Base(f), err)
		}
		for _, cat := range []string{"logical", "weird", "textual", "hack"} {
			if strings.Contains(string(b), cat+" attack") {
				seen[cat] = true
			}
		}
	}
	for _, cat := range []string{"logical", "weird", "textual", "hack"} {
		if !seen[cat] {
			t.Errorf("REST corpus does not cover category %q; raise corpusRestSeedMax and regenerate", cat)
		}
	}
}

// TestCsvCorpusCoversAllCategories guards that the CSV families (seed_* + run_*)
// exercise all four input categories independently of REST or other lanes.
// Without this, REST files could mask a missing CSV category in the broader scan.
func TestCsvCorpusCoversAllCategories(t *testing.T) {
	seen := map[string]bool{}
	for _, pattern := range []string{"../corpus/seed_*.feature", "../corpus/run_*.feature"} {
		files, err := filepath.Glob(pattern)
		if err != nil {
			t.Fatalf("glob %s: %v", pattern, err)
		}
		for _, f := range files {
			b, err := os.ReadFile(f)
			if err != nil {
				t.Fatalf("read corpus file %s: %v", filepath.Base(f), err)
			}
			for _, cat := range []string{"logical", "weird", "textual", "hack"} {
				if strings.Contains(string(b), cat+" attack") {
					seen[cat] = true
				}
			}
		}
	}
	for _, cat := range []string{"logical", "weird", "textual", "hack"} {
		if !seen[cat] {
			t.Errorf("CSV corpus (seed_*/run_*) does not cover category %q; raise corpusSeedMax and regenerate", cat)
		}
	}
}
