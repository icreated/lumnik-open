package cli

import (
	"bytes"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/textsafe"

	"github.com/spf13/cobra"
)

// chatCmdWritingTo builds a command whose stdout is wrapped exactly as root.Run wraps the real
// one, so a test sees what a terminal would see rather than what the call site handed over.
func chatCmdWritingTo(raw *bytes.Buffer) *cobra.Command {
	cmd := &cobra.Command{}
	cmd.SetOut(textsafe.NewWriter(raw))
	cmd.SetErr(textsafe.NewWriter(new(bytes.Buffer)))
	return cmd
}

// :clear is an escape sequence, and lm escapes control runes on the way out — ESC is one. Written
// to the guarded writer the sequence arrives as visible \u001B text and the screen never clears.
func TestHandleSlash_clearReachesTheTerminalUnescaped(t *testing.T) {
	var raw bytes.Buffer
	cmd := chatCmdWritingTo(&raw)

	if exit := handleSlash(cmd, &chatSession{topK: 4}, ":clear"); exit {
		t.Fatal(":clear must not end the session")
	}

	got := raw.String()
	if !strings.Contains(got, "\x1b[H\x1b[2J") {
		t.Errorf(":clear must reach the terminal as a real escape sequence, got %q", got)
	}
	if strings.Contains(got, "u001B") || strings.Contains(got, "u001b") {
		t.Errorf(":clear arrived escaped — the screen would not clear, got %q", got)
	}
}

// The bypass is for our own constant only. Everything a human or a server typed keeps the guard,
// and the two facts have to be pinned together: "fixing" one alone breaks the other.
func TestHandleSlash_userTextStaysGuarded(t *testing.T) {
	var raw bytes.Buffer
	cmd := chatCmdWritingTo(&raw)

	handleSlash(cmd, &chatSession{topK: 4}, ":corpus ventes"+string(rune(0x202E))+"evil")

	got := raw.String()
	if strings.ContainsRune(got, 0x202E) {
		t.Errorf("an RTL override typed at the prompt must not reach the terminal, got %q", got)
	}
	if !strings.Contains(got, "202E") {
		t.Errorf("it should arrive escaped and visible, got %q", got)
	}
}

// `:scope TAG` — the REPL's half of the métier boundary.
//
// The HTTP door takes a `scope` field and `lm ask` has `--scope`, but the chat REPL offered only
// `:corpus` and `:topK`: the sole way to aim it at a métier was `lm scope set <tag>` BEFORE
// starting, and with no default it printed "(no default scope — fallback to 'system')" and there
// was nothing to do about it from inside (#493).
func TestHandleSlash_scopeSetsAndShowsTheMetier(t *testing.T) {
	var raw bytes.Buffer
	cmd := chatCmdWritingTo(&raw)
	s := &chatSession{topK: 4}

	handleSlash(cmd, s, ":scope magasin")

	if s.scope != "magasin" {
		t.Errorf("session scope = %q, want %q", s.scope, "magasin")
	}
	if !strings.Contains(raw.String(), "magasin") {
		t.Errorf("the change must be echoed, got %q", raw.String())
	}
}

// Bare `:scope` reports rather than clears — the shape `:corpus` and `:topK` already have.
func TestHandleSlash_bareScopeReportsWithoutChanging(t *testing.T) {
	var raw bytes.Buffer
	cmd := chatCmdWritingTo(&raw)
	s := &chatSession{topK: 4, scope: "magasin"}

	handleSlash(cmd, s, ":scope")

	if s.scope != "magasin" {
		t.Errorf("a bare :scope must not change anything, got %q", s.scope)
	}
	if !strings.Contains(raw.String(), "magasin") {
		t.Errorf("it must report the current scope, got %q", raw.String())
	}
}

// 'default' hands the question back to the server, which falls back to the user's `lm scope set`
// value — the same escape hatch `:corpus all` gives.
func TestHandleSlash_scopeDefaultClearsTheBinding(t *testing.T) {
	var raw bytes.Buffer
	cmd := chatCmdWritingTo(&raw)
	s := &chatSession{topK: 4, scope: "magasin"}

	handleSlash(cmd, s, ":scope default")

	if s.scope != "" {
		t.Errorf("'default' must clear the session binding, got %q", s.scope)
	}
}

// The guard `:corpus` has, on the sibling command — a tag is user text on both.
func TestHandleSlash_scopeTextStaysGuarded(t *testing.T) {
	var raw bytes.Buffer
	cmd := chatCmdWritingTo(&raw)

	handleSlash(cmd, &chatSession{topK: 4}, ":scope magasin"+string(rune(0x202E))+"evil")

	got := raw.String()
	if strings.ContainsRune(got, 0x202E) {
		t.Errorf("an RTL override typed at the prompt must not reach the terminal, got %q", got)
	}
	if !strings.Contains(got, "202E") {
		t.Errorf("it should arrive escaped and visible, got %q", got)
	}
}

// :help is the only place a REPL command is discoverable.
func TestHandleSlash_helpListsScope(t *testing.T) {
	var raw bytes.Buffer
	cmd := chatCmdWritingTo(&raw)

	handleSlash(cmd, &chatSession{topK: 4}, ":help")

	if !strings.Contains(raw.String(), ":scope") {
		t.Errorf(":help must list :scope, got %q", raw.String())
	}
}
