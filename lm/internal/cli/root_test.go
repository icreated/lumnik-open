package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"testing"

	"github.com/spf13/cobra"
)

// The returned-error path is the one that produces today's 3 OTK findings:
// a hub error body with a raw bidi/NUL rune, printed on exit.
func TestRun_escapesRuneInReturnedError(t *testing.T) {
	bad := "API error 400: bad name a" + string(rune(0x202E)) + "b" + string(rune(0x00)) + "c"
	root := &cobra.Command{
		Use: "x", SilenceErrors: true, SilenceUsage: true,
		RunE: func(*cobra.Command, []string) error { return fmt.Errorf("%s", bad) },
	}
	root.SetArgs([]string{})
	var out, errb bytes.Buffer
	if code := Run(root, &out, &errb); code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
	s := errb.String()
	if strings.ContainsRune(s, rune(0x202E)) || strings.ContainsRune(s, rune(0x00)) {
		t.Fatalf("raw dangerous rune leaked to stderr: %q", s)
	}
	if !strings.Contains(s, "202E") || !strings.Contains(s, "0000") {
		t.Fatalf("expected escaped forms in stderr, got %q", s)
	}
}

func TestRun_escapesRuneInStdout(t *testing.T) {
	root := &cobra.Command{
		Use: "x", SilenceErrors: true, SilenceUsage: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			fmt.Fprintf(cmd.OutOrStdout(), "name: a%sb\n", string(rune(0x202E)))
			return nil
		},
	}
	root.SetArgs([]string{})
	var out, errb bytes.Buffer
	if code := Run(root, &out, &errb); code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}
	if strings.ContainsRune(out.String(), rune(0x202E)) {
		t.Fatalf("raw bidi leaked to stdout: %q", out.String())
	}
	if !strings.Contains(out.String(), "202E") {
		t.Fatalf("expected escaped form in stdout, got %q", out.String())
	}
}

// Deliberate machine output (a -o json artifact / raw server body) must stay
// byte-exact even though Run wraps stdout in a sanitizer: it goes through
// machineOut, which unwraps. This guards the spec's machine-output contract.
func TestRun_machineOutStaysByteExact(t *testing.T) {
	root := &cobra.Command{
		Use: "x", SilenceErrors: true, SilenceUsage: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			return json.NewEncoder(machineOut(cmd)).Encode(map[string]string{"name": "a" + string(rune(0x202E)) + "b"})
		},
	}
	root.SetArgs([]string{})
	var out, errb bytes.Buffer
	if code := Run(root, &out, &errb); code != 0 {
		t.Fatalf("exit=%d want 0; stderr=%q", code, errb.String())
	}
	if !bytes.ContainsRune(out.Bytes(), rune(0x202E)) {
		t.Fatalf("machine output must stay byte-exact (raw bidi), got %q", out.String())
	}
}

// Every command says what it does in its parent's --help: an empty Short is a blank line there
// (`lm connector --help` showed five, #1175).
func TestEveryCommandHasAShortLine(t *testing.T) {
	var walk func(c *cobra.Command)
	walk = func(c *cobra.Command) {
		if c.Short == "" && !c.Hidden && c.Name() != "help" {
			t.Errorf("%s has no Short", c.CommandPath())
		}
		for _, sub := range c.Commands() {
			walk(sub)
		}
	}
	walk(NewRootCmd())
}

// --help speaks to the integrator: no issue number, no plan or task of the project, no "(stub)".
var engineWords = regexp.MustCompile(`#[0-9]+|\bTask [0-9]+|\bPlan [0-9]+|\(stub\)`)

func TestHelpTextsCarryNoProjectBookkeeping(t *testing.T) {
	var walk func(c *cobra.Command)
	walk = func(c *cobra.Command) {
		if !c.Hidden {
			for _, text := range []string{c.Short, c.Long} {
				if w := engineWords.FindString(text); w != "" {
					t.Errorf("%s: help text carries %q: %q", c.CommandPath(), w, text)
				}
			}
		}
		for _, sub := range c.Commands() {
			walk(sub)
		}
	}
	walk(NewRootCmd())
}

// The commands that do nothing yet stay callable (a script may call them) but out of --help, and
// their refusal names no plan or task of the project.
func TestStubCommandsAreHiddenAndRefusePlainly(t *testing.T) {
	setupCfg(t, "http://127.0.0.1:0")
	for _, args := range [][]string{
		{"source", "rest", "run", "acme", "--endpoint", "e"},
		{"hook", "generate"},
		{"hook", "apply"},
		{"hook", "delete", "h1"},
		{"hook", "reload", "h1"},
	} {
		t.Run(strings.Join(args, " "), func(t *testing.T) {
			root := NewRootCmd()
			cmd, _, err := root.Find(args)
			if err != nil {
				t.Fatal(err)
			}
			if !cmd.Hidden {
				t.Errorf("%s is shown in --help", cmd.CommandPath())
			}
			root.SetOut(new(bytes.Buffer))
			root.SetErr(new(bytes.Buffer))
			root.SetArgs(args)
			err = root.Execute()
			if err == nil {
				t.Fatal("expected a refusal")
			}
			if w := engineWords.FindString(err.Error()); w != "" {
				t.Errorf("refusal carries %q: %v", w, err)
			}
		})
	}
}
