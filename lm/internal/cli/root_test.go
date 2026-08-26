package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
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
