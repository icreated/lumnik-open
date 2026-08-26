//go:build otk

package otk

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// TestEveryCorpusStepIsBound asserts that every Gherkin step line in the committed
// corpus matches exactly one registered step pattern. This closes the gap the
// golden lock leaves open: a generated step with no binding would otherwise pass
// generator↔corpus equality yet fail the live suite as "undefined".
func TestEveryCorpusStepIsBound(t *testing.T) {
	res := make([]*regexp.Regexp, len(stepPatterns))
	for i, p := range stepPatterns {
		res[i] = regexp.MustCompile(p)
	}

	files, err := filepath.Glob("corpus/*.feature")
	if err != nil || len(files) == 0 {
		t.Fatalf("no corpus files found: %v", err)
	}
	keywords := []string{"Given ", "When ", "Then ", "And ", "But "}
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatalf("read %s: %v", f, err)
		}
		for _, raw := range strings.Split(string(b), "\n") {
			line := strings.TrimSpace(raw)
			var text string
			for _, kw := range keywords {
				if strings.HasPrefix(line, kw) {
					text = strings.TrimPrefix(line, kw)
					break
				}
			}
			if text == "" {
				continue // not a step line
			}
			matches := 0
			for _, re := range res {
				if re.MatchString(text) {
					matches++
				}
			}
			if matches != 1 {
				t.Errorf("%s: step %q matched %d patterns, want exactly 1", filepath.Base(f), text, matches)
			}
		}
	}
}
