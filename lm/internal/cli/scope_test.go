package cli

import "testing"

func TestNewScopeCmdHasSetSubcommand(t *testing.T) {
	cmd := newScopeCmd()
	if cmd.Use != "scope" {
		t.Errorf("expected Use=scope, got %q", cmd.Use)
	}
	found := false
	for _, sub := range cmd.Commands() {
		if sub.Use == "set TAG" {
			found = true
		}
	}
	if !found {
		t.Error("expected 'set TAG' subcommand")
	}
}

func TestNewScopesCmdExists(t *testing.T) {
	cmd := newScopesCmd()
	if cmd.Use != "scopes" {
		t.Errorf("expected Use=scopes, got %q", cmd.Use)
	}
}
