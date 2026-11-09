package cli

import (
	"bytes"
	"strings"
	"testing"
)

// A value holding a line break stays on its line (#1889): the label list keeps one line per label.
func TestPadWriter_showsALineBreakInAValueInsteadOfObeyingIt(t *testing.T) {
	p := newPadWriter()
	p.line("NOTES", "Broche révisée.\nPrévoir 2027.")
	p.line("ID", "M-1")
	var out bytes.Buffer
	p.flush(&out)
	lines := strings.Split(strings.TrimSuffix(out.String(), "\n"), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], `Broche révisée.\nPrévoir 2027.`) {
		t.Errorf("one label, one line, break shown: %q", out.String())
	}
}
