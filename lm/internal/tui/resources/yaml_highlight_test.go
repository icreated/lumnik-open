package resources

import (
	"strings"
	"testing"
)

func TestHighlightYAML(t *testing.T) {
	in := "# a comment\nname: shop\nendpoints:\n  - id: orders\n    kind: graphql\n"
	out := highlightYAML(in)

	// comments dimmed
	if !strings.Contains(out, "[gray]# a comment") {
		t.Errorf("comment not dimmed:\n%s", out)
	}
	// keys colored, values left plain
	if !strings.Contains(out, "[yellow]name[white]: shop") {
		t.Errorf("top-level key not colored:\n%s", out)
	}
	if !strings.Contains(out, "[yellow]id[white]: orders") {
		t.Errorf("nested list key not colored:\n%s", out)
	}
	// structure preserved: same number of lines
	if got, want := strings.Count(out, "\n"), strings.Count(in, "\n"); got != want {
		t.Errorf("line count changed: got %d want %d", got, want)
	}
}

func TestHighlightYAMLEscapesBrackets(t *testing.T) {
	// A value that looks like a tview tag must be escaped so it renders literally.
	// tview.Escape guards a "[...]" region by inserting '[' before the ']'.
	out := highlightYAML("tags: [demo, crm]\n")
	if !strings.Contains(out, "[demo, crm[]") {
		t.Errorf("bracketed value not tview-escaped:\n%s", out)
	}
}
