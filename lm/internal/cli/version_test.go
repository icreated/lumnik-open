package cli

import (
	"bytes"
	"strings"
	"testing"
)

func TestVersionCommand(t *testing.T) {
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"version"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("version returned error: %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "lm version") {
		t.Errorf("expected 'lm version' in output, got: %q", out)
	}
}
