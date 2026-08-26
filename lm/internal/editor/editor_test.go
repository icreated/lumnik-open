package editor

import (
	"strings"
	"testing"
)

func TestEditYAML_RoundTripWithCmd(t *testing.T) {
	t.Setenv("LM_EDITOR", "true")

	original := "name: foo\ncount: 5\n"
	got, changed, err := EditYAML("connector.yaml", original)
	if err != nil {
		t.Fatalf("EditYAML: %v", err)
	}
	if changed {
		t.Errorf("expected no-change when editor leaves file untouched, got changed=true")
	}
	if got != original {
		t.Errorf("content modified despite no edit: got %q", got)
	}
}

func TestEditYAML_DetectsModification(t *testing.T) {
	t.Setenv("LM_EDITOR", "sh -c \"printf '\\nedited: true\\n' >> \\\"$1\\\"\" --")

	got, changed, err := EditYAML("c.yaml", "name: bar\n")
	if err != nil {
		t.Fatalf("EditYAML: %v", err)
	}
	if !changed {
		t.Error("expected changed=true after editor appended content")
	}
	if !strings.Contains(got, "edited: true") {
		t.Errorf("expected edited content, got %q", got)
	}
}

func TestEditYAML_NoEditorAvailable_ReturnsError(t *testing.T) {
	t.Setenv("LM_EDITOR", "")
	t.Setenv("EDITOR", "")
	t.Setenv("PATH", t.TempDir())

	_, _, err := EditYAML("c.yaml", "x: 1\n")
	if err == nil {
		t.Error("expected error when no editor available, got nil")
	}
}
