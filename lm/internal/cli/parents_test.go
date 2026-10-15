package cli

import (
	"bytes"
	"strings"
	"testing"

	"github.com/spf13/cobra"
)

// parentPaths lists, from the tree as commands declare themselves, the path of every command that only GROUPS
// subcommands (no Run of its own, the root excluded): the inventory the two tests below walk, so a parent added
// tomorrow is covered without anyone remembering it (#1064). A command with its own Run (`lm view -f`, `lm inbox`)
// is not a grouping parent and keeps its own behaviour.
func parentPaths() [][]string {
	var out [][]string
	var walk func(c *cobra.Command, path []string)
	walk = func(c *cobra.Command, path []string) {
		for _, sub := range c.Commands() {
			p := append(append([]string{}, path...), sub.Name())
			if sub.HasSubCommands() && !sub.Runnable() {
				out = append(out, p)
			}
			walk(sub, p)
		}
	}
	walk(buildRootCmd(), nil)
	return out
}

func execArgs(t *testing.T, args ...string) (string, error) {
	t.Helper()
	t.Setenv("HOME", t.TempDir()) // hermetic: never the developer's own ~/.lm (contexts, tokens)
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs(args)
	err := cmd.Execute()
	return buf.String(), err
}

// A parent without a Run prints its help and exits 0 for ANY argument — so `lm connector lst` looked like a success
// to a script. Every parent, nested ones included, must refuse a word that is not one of its subcommands.
func TestEveryParentCommandRejectsAnUnknownWord(t *testing.T) {
	parents := parentPaths()
	if len(parents) < 10 {
		t.Fatalf("the walk found only %d parent commands — the inventory is broken", len(parents))
	}
	for _, p := range parents {
		out, err := execArgs(t, append(append([]string{}, p...), "notasubcommand")...)
		if err == nil {
			t.Errorf("lm %s notasubcommand exited 0 (printed %d bytes) — it must be an error", strings.Join(p, " "), len(out))
			continue
		}
		if !strings.Contains(err.Error(), "unknown command") || !strings.Contains(err.Error(), "notasubcommand") {
			t.Errorf("lm %s: want `unknown command \"notasubcommand\"…`, got %v", strings.Join(p, " "), err)
		}
	}
}

// Cobra adds its own `completion` parent lazily, at Execute — after any walk that runs at construction. It has the
// same defect (NoArgs but no Run), so the tree is given it up front and the inventory sees it.
func TestTheCompletionParentRejectsAnUnknownWordToo(t *testing.T) {
	if _, err := execArgs(t, "completion", "notasubcommand"); err == nil {
		t.Fatal("lm completion notasubcommand exited 0 — it must be an error")
	}
	found := false
	for _, p := range parentPaths() {
		found = found || (len(p) == 1 && p[0] == "completion")
	}
	if !found {
		t.Error("the inventory does not list `completion`: it would not notice a regression there")
	}
}

// ...and the bare parent is still its own help, exit 0 — the discovery gesture does not change.
func TestEveryParentCommandStillPrintsItsHelpWhenBare(t *testing.T) {
	for _, p := range parentPaths() {
		out, err := execArgs(t, p...)
		if err != nil {
			t.Errorf("bare `lm %s` must print its help, got the error %v", strings.Join(p, " "), err)
			continue
		}
		if !strings.Contains(out, "Usage:") || !strings.Contains(out, "Available Commands:") {
			t.Errorf("bare `lm %s` should list its subcommands:\n%s", strings.Join(p, " "), out)
		}
	}
}
