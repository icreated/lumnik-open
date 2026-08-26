package tui

import (
	"strings"
	"testing"
)

// The in-app help is the TUI's only documentation: every palette page
// must have a help row, or the page is undiscoverable.
func TestHelpCoversAllPaletteEntries(t *testing.T) {
	for _, entry := range paletteEntries {
		if !strings.Contains(helpBody, ":"+entry) {
			t.Errorf("help body missing palette page :%s", entry)
		}
	}
}

func TestHelpTeachesViewGenerate(t *testing.T) {
	if !strings.Contains(helpBody, "generate") {
		t.Error("the ? help must mention the :view generate gesture")
	}
}

// The a gesture completes loop 2 (generate → render → apply into the saved-view store);
// the page footer teaches it, the ? help must too.
func TestHelpTeachesViewApply(t *testing.T) {
	if !strings.Contains(helpBody, "apply to hub") {
		t.Error("the ? help must mention the :view a (apply to hub) gesture")
	}
}

// Two registers, one nav: the help tells the product story in two chapters.
func TestHelpHasTwoChapters(t *testing.T) {
	for _, chapter := range []string{"Your data", "Workshop"} {
		if !strings.Contains(helpBody, chapter) {
			t.Errorf("help body missing chapter %q", chapter)
		}
	}
}

// The alias must be discoverable, or renaming breaks trained fingers silently.
func TestHelpMentionsConnectorsAlias(t *testing.T) {
	if !strings.Contains(helpBody, "alias :connectors") {
		t.Error("help body must mention the :connectors alias on the :sources row")
	}
}
