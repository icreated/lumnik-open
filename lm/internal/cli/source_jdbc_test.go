package cli

import (
	"strings"
	"testing"
)

func TestSourceJdbcSubcommandsRegistered(t *testing.T) {
	cmd := newSourceJdbcCmd()
	got := map[string]bool{}
	for _, sub := range cmd.Commands() {
		got[sub.Name()] = true
	}
	want := []string{"test", "discover", "add", "list-tables"}
	for _, w := range want {
		if !got[w] {
			t.Errorf("expected subcommand %q registered, have %v", w, got)
		}
	}
}

func TestSourceCmdContainsJdbc(t *testing.T) {
	cmd := newSourceCmd()
	got := map[string]bool{}
	for _, sub := range cmd.Commands() {
		got[sub.Name()] = true
	}
	if !got["jdbc"] {
		t.Errorf("source command missing jdbc subcommand, have %v", got)
	}
}

func TestReadPasswordStdinTrimsNewline(t *testing.T) {
	got := strings.TrimRight("secret\r\n", "\r\n")
	if got != "secret" {
		t.Errorf("trim failed: %q", got)
	}
}

func TestSourceJdbcAddRequiresArgs(t *testing.T) {
	cmd := newSourceJdbcAddCmd()
	if cmd.Args == nil {
		t.Error("expected Args validator on add command")
	}
}
