package proposal

import (
	"os"
	"path/filepath"
	"testing"
)

// repoRoot finds the checkout by the corpus catalogue, the way the hub's tests find it. It fails,
// never skips: a silently skipped replay is the exact failure the corpus exists to prevent.
func repoRoot(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for ; ; dir = filepath.Dir(dir) {
		if _, err := os.Stat(filepath.Join(dir, "lumnik-hub/src/main/resources/proposal/catalogue.json")); err == nil {
			return dir
		}
		if dir == filepath.Dir(dir) {
			t.Fatal("cannot find lumnik-hub/src/main/resources/proposal/catalogue.json above the working directory")
		}
	}
}
