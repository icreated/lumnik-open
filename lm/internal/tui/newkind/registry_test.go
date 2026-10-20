package newkind

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRegistryComplete(t *testing.T) {
	kinds := All()
	if len(kinds) != 13 {
		t.Fatalf("expected 13 kinds (App, View, Export, SearchIndex join in #1118), got %d", len(kinds))
	}
	for _, k := range kinds {
		if k.Template == "" || k.Doc == "" || k.Observe == "" || k.Source == "" {
			t.Errorf("kind %q has an empty field", k.Kind)
		}
	}
}

// The templates are COPIES (go:embed can't leave the module) — this guard keeps them
// byte-identical to their canonical doc files, the same "docs can't lie" property
// the site gets from transclusion.
func TestTemplatesMatchCanonicalDocs(t *testing.T) {
	root := repoRoot(t)
	for _, k := range All() {
		canon, err := os.ReadFile(filepath.Join(root, k.Source))
		if err != nil {
			t.Fatalf("%s: canonical file unreadable: %v", k.Kind, err)
		}
		if string(canon) != k.Template {
			t.Errorf("%s: embedded template drifted from %s — re-copy it", k.Kind, k.Source)
		}
	}
}

func repoRoot(t *testing.T) string {
	dir, _ := os.Getwd()
	for !strings.HasSuffix(dir, string(filepath.Separator)+"lm") {
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("repo root not found")
		}
		dir = parent
	}
	return filepath.Dir(dir)
}
