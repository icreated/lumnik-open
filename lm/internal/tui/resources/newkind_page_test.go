package resources

import (
	"errors"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/tui/newkind"
)

var errFake = errors.New("no editor")

func TestNewKindPageListsAllKinds(t *testing.T) {
	p := &NewKind{kinds: newkind.All()}
	if len(p.kinds) != 9 {
		t.Fatalf("picker must list the 9 registry kinds, got %d", len(p.kinds))
	}
	if _, _, ok := (&NewKind{}).SelectedDescribe(); ok {
		t.Error("no selection on an uninitialized page")
	}
}

// The page is a two-state machine: picker ↔ issues. Abandoning from issues
// must return to the picker AND keep the buffer in a temp file (never lost).
// A failing $EDITOR must NOT eject the user from the issues view — the buffer
// would become unreachable (review round 3).
func TestNewKindEditorErrorKeepsIssuesMode(t *testing.T) {
	p := &NewKind{kinds: newkind.All(),
		suspend: func(f func()) { f() },
		editFn: func(hint, content string) (string, bool, error) {
			return "", false, errFake
		}}
	p.enterIssues("issues", "kind: Connector\n# my edits")
	p.reEdit()
	if p.mode != modeIssues {
		t.Error("editor failure must keep the issues mode (buffer stays reachable)")
	}
	if p.buffer == "" {
		t.Error("editor failure must keep the buffer")
	}
}

// EditExisting is the :processes 'e' entry: same loop, existing buffer.
func TestEditExistingResolvesTheKind(t *testing.T) {
	p := &NewKind{kinds: newkind.All(),
		suspend: func(f func()) { f() },
		editFn: func(hint, content string) (string, bool, error) {
			if content != "kind: Process\n# stored" {
				t.Errorf("EditExisting must open the STORED manifest, got %q", content)
			}
			return "", false, errFake // editor fails: loop must stop safely
		}}
	if ok := p.EditExisting("Process", "kind: Process\n# stored"); !ok {
		t.Fatal("Process is a registry kind — EditExisting must accept it")
	}
	if p.current.Kind != "Process" {
		t.Errorf("current kind = %q, want Process", p.current.Kind)
	}
	if ok := p.EditExisting("NoSuchKind", "x"); ok {
		t.Error("unknown kind must be refused")
	}
}

func TestNewKindModeTransitions(t *testing.T) {
	t.Setenv("TMPDIR", t.TempDir()) // abandon()'s temp file is cleaned with the test
	p := &NewKind{kinds: newkind.All()}
	if p.mode != modePick {
		t.Fatal("initial mode must be the picker")
	}
	p.enterIssues("some issues", "kind: Connector\n# my edits")
	if p.mode != modeIssues {
		t.Fatal("enterIssues must switch mode")
	}
	path := p.abandon()
	if p.mode != modePick {
		t.Error("abandon must return to the picker")
	}
	if path == "" || !strings.Contains(path, "lm-new-") {
		t.Errorf("abandon must persist the buffer to a temp file, got %q", path)
	}
}
