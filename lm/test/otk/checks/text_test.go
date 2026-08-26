package checks

import (
	"fmt"
	"testing"

	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

func TestTextGlitch_RawBidiIsLow(t *testing.T) {
	got := TextGlitch("name otk\u202eadv invalid", "", 3) // raw U+202E in the string value
	if len(got) != 1 || got[0].Severity != oracle.Low || got[0].Family != oracle.Text {
		t.Fatalf("raw bidi rune must be LOW/text, got %v", got)
	}
}

func TestTextGlitch_EscapedBidiIsClean(t *testing.T) {
	// The %q-escaped token is plain ASCII (backslash-u-202e) and is SAFE.
	out := fmt.Sprintf("name %q invalid", "otk\u202eadv")
	if got := TextGlitch(out, "", 3); len(got) != 0 {
		t.Errorf("escaped bidi must be clean, got %v", got)
	}
}

func TestTextGlitch_NulIsLow(t *testing.T) {
	if got := TextGlitch("", "bad\x00name", 1); len(got) != 1 || got[0].Severity != oracle.Low {
		t.Fatalf("NUL must be LOW, got %v", got)
	}
}

func TestTextGlitch_PlainAsciiIsClean(t *testing.T) {
	if got := TextGlitch("error: invalid name 'drop table'\n", "", 1); len(got) != 0 {
		t.Errorf("plain ascii (incl. \\t\\n\\r) must be clean, got %v", got)
	}
}
