package ask

import (
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

// The oracle at contracts/ask-door-tokens.txt is the cross-language pin for AskResult.DOORS
// (#522): a rename on the Java side without updating it here breaks THIS test, instead of
// silently dropping the gesture in `lm ask` and the TUI's :ask page.
func TestAllMatchesTheCrossLanguageOracle(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "ask-door-tokens.txt"))
	if err != nil {
		t.Fatalf("reading the oracle: %v", err)
	}
	oracle := nonEmptyLines(string(raw))

	got := append([]string(nil), All...)
	sort.Strings(got)
	sort.Strings(oracle)

	if !reflect.DeepEqual(got, oracle) {
		t.Errorf("ask.All %v does not match contracts/ask-door-tokens.txt %v", got, oracle)
	}
}

func nonEmptyLines(s string) []string {
	var out []string
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			out = append(out, line)
		}
	}
	return out
}
