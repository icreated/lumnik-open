//go:build otk

package otk

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// compiledStepPatterns compiles the registered step patterns once for reuse across
// both binding guards.
func compiledStepPatterns() []*regexp.Regexp {
	res := make([]*regexp.Regexp, len(stepPatterns))
	for i, p := range stepPatterns {
		res[i] = regexp.MustCompile(p)
	}
	return res
}

// stepBindingIssues returns one message per Gherkin step line in contents that does
// not match exactly one of res — unbound (0 matches) or ambiguous (>1 matches).
func stepBindingIssues(res []*regexp.Regexp, contents string) []string {
	var issues []string
	keywords := []string{"Given ", "When ", "Then ", "And ", "But "}
	for _, raw := range strings.Split(contents, "\n") {
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
			issues = append(issues, fmt.Sprintf("step %q matched %d patterns, want exactly 1", text, matches))
		}
	}
	return issues
}

// TestEveryCorpusStepIsBound asserts that every Gherkin step line in the committed
// corpus matches exactly one registered step pattern. This closes the gap the
// golden lock leaves open: a generated step with no binding would otherwise pass
// generator↔corpus equality yet fail the live suite as "undefined".
func TestEveryCorpusStepIsBound(t *testing.T) {
	res := compiledStepPatterns()

	files, err := filepath.Glob("corpus/*.feature")
	if err != nil || len(files) == 0 {
		t.Fatalf("no corpus files found: %v", err)
	}
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatalf("read %s: %v", f, err)
		}
		for _, issue := range stepBindingIssues(res, string(b)) {
			t.Errorf("%s: %s", filepath.Base(f), issue)
		}
	}
}

// TestEveryGoldCatalogueStepIsBound closes the gap the corpus-only guard still left:
// Gold does not run the committed corpus/ directory, it runs catalogueFeatures() —
// one feature per catalogue case, rendered from that case's witness seed. godog is
// not run in strict mode, so a Gold feature whose step binds to nothing would run
// zero assertions and the gate would still report success. This test is hub-free
// (the witness sweep is pure CPU, see generator.Witnesses) so it runs in otk-unit,
// on every pull request, catching the gap before a Gold run ever needs to.
func TestEveryGoldCatalogueStepIsBound(t *testing.T) {
	res := compiledStepPatterns()

	feats, err := catalogueFeatures()
	if err != nil {
		t.Fatalf("catalogueFeatures(): %v", err)
	}
	if len(feats) == 0 {
		t.Fatal("catalogueFeatures() produced no features")
	}
	for _, f := range feats {
		for _, issue := range stepBindingIssues(res, string(f.Contents)) {
			t.Errorf("%s: %s", f.Name, issue)
		}
	}
}
