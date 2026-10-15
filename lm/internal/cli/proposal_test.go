package cli

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func minimalDossier(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	require.NoError(t, err)
	for ; dir != filepath.Dir(dir); dir = filepath.Dir(dir) {
		p := filepath.Join(dir, "lumnik-hub/src/test/resources/proposal/fixtures/valid/minimal.md")
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	t.Fatal("cannot find the corpus above the working directory")
	return ""
}

// runDraft runs the real root command with no hub configured and no token: if it needed either,
// it would fail here.
func runDraft(t *testing.T, stdin string, args ...string) (stdout, stderr string, err error) {
	t.Helper()
	t.Setenv("HOME", t.TempDir())
	root := NewRootCmd()
	var out, errb bytes.Buffer
	root.SetIn(strings.NewReader(stdin))
	root.SetOut(&out)
	root.SetErr(&errb)
	root.SetArgs(append([]string{"proposal", "draft"}, args...))
	err = root.Execute()
	return out.String(), errb.String(), err
}

func TestProposalDraft_writesADraftOffline(t *testing.T) {
	out, _, err := runDraft(t, "", minimalDossier(t), "--table", "connector.t_commandes", "--column", "statut", "--after", "14d")
	require.NoError(t, err)
	assert.Contains(t, out, "kind: Workflow")
	assert.Contains(t, out, `table: "connector.t_commandes"`)
	assert.Contains(t, out, `after: "14d"`)
	assert.NotContains(t, out, "\n  states:")
}

func TestProposalDraft_readsStdin(t *testing.T) {
	raw, err := os.ReadFile(minimalDossier(t))
	require.NoError(t, err)
	out, _, err := runDraft(t, string(raw), "-")
	require.NoError(t, err)
	assert.Contains(t, out, "kind: Workflow")
}

func TestProposalDraft_refusesWithTheContractReason(t *testing.T) {
	_, _, err := runDraft(t, "---\n{\"a\":1,\"a\":2}\n---\n", "-")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "duplicate-key")
}

func TestProposalDraft_refusesAdossierWithoutTheLead(t *testing.T) {
	raw, err := os.ReadFile(minimalDossier(t))
	require.NoError(t, err)
	other := strings.Replace(string(raw), "stalled-documents@1", "another-rule@1", 1)
	_, _, err = runDraft(t, other, "-")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "stalled-documents@1")
}

func TestProposalDraft_namesTheLeadsItDidNotDraft(t *testing.T) {
	raw, err := os.ReadFile(minimalDossier(t))
	require.NoError(t, err)
	text := string(raw)
	start := strings.Index(text, "    {\n      \"id\": \"L1\"")
	require.GreaterOrEqual(t, start, 0, "the minimal fixture's lead layout changed")
	end := strings.Index(text, "\n  ]\n}\n---")
	require.Greater(t, end, start)
	lead := text[start:end]
	second := strings.NewReplacer(`"L1"`, `"L2"`, "stalled-documents@1", "another-rule@1",
		`"C1"`, `"D1"`, `"C2"`, `"D2"`, `"C3"`, `"D3"`, `"C4"`, `"D4"`, `"C5"`, `"D5"`).Replace(lead)
	_, stderr, err := runDraft(t, text[:end]+",\n"+second+text[end:], "-")
	require.NoError(t, err)
	assert.Contains(t, stderr, "another-rule@1")
}

func TestProposalDraft_flagValidation(t *testing.T) {
	for name, args := range map[string][]string{
		"after shape":   {"--after", "soon"},
		"table no dot":  {"--table", "t_commandes"},
		"empty flag":    {"--table", ""},
		"newline value": {"--column", "a\nb"},
		"empty output":  {"-o", ""},
	} {
		t.Run(name, func(t *testing.T) {
			_, _, err := runDraft(t, "", append([]string{minimalDossier(t)}, args...)...)
			assert.Error(t, err)
		})
	}
}

func TestProposalDraft_outputFileIsCreatedAndNeverOverwritten(t *testing.T) {
	target := filepath.Join(t.TempDir(), "draft.yaml")

	out, _, err := runDraft(t, "", minimalDossier(t), "-o", target)
	require.NoError(t, err)
	assert.NotContains(t, out, "kind: Workflow", "with -o the YAML goes to the file, not to stdout")
	written, err := os.ReadFile(target)
	require.NoError(t, err)
	assert.Contains(t, string(written), "kind: Workflow")

	// the integrator edits her draft...
	require.NoError(t, os.WriteFile(target, []byte("my edits\n"), 0o644))
	_, _, err = runDraft(t, "", minimalDossier(t), "-o", target)
	require.Error(t, err)
	kept, err := os.ReadFile(target)
	require.NoError(t, err)
	assert.Equal(t, "my edits\n", string(kept), "an existing file is left byte-identical")

	// ...also when the dossier is the thing that is wrong
	_, _, err = runDraft(t, "not a dossier", "-", "-o", target)
	require.Error(t, err)
	kept, _ = os.ReadFile(target)
	assert.Equal(t, "my edits\n", string(kept))
}

func TestProposalDraft_aRefusedDossierCreatesNoFile(t *testing.T) {
	target := filepath.Join(t.TempDir(), "draft.yaml")
	_, _, err := runDraft(t, "not a dossier", "-", "-o", target)
	require.Error(t, err)
	_, statErr := os.Stat(target)
	assert.True(t, os.IsNotExist(statErr))
}

// The command must not be able to reach a hub: not by configuration (HOME is empty above), and
// not by import.
func TestProposalPackageHasNoNetworkDependency(t *testing.T) {
	out, err := exec.Command("go", "list", "-deps", "../proposal").CombinedOutput()
	require.NoError(t, err, string(out))
	deps := string(out)
	assert.NotContains(t, deps, "net/http\n")
	assert.NotContains(t, deps, "lm/internal/client")
}

// The JSON text of an ignored lead's rule may carry a newline and a bidi override (valid JSON
// strings): stderr must still be one safe line for it, not a line the dossier wrote.
func TestProposalDraft_aHostileIgnoredRuleCannotWriteLinesToStderr(t *testing.T) {
	raw, err := os.ReadFile(minimalDossier(t))
	require.NoError(t, err)
	text := string(raw)
	start := strings.Index(text, "    {\n      \"id\": \"L1\"")
	require.GreaterOrEqual(t, start, 0, "the minimal fixture's lead layout changed")
	end := strings.Index(text, "\n  ]\n}\n---")
	require.Greater(t, end, start)
	hostileRule := "other@1" + `\n` + "warning: applied" + `\u202e` + "!" // JSON escapes, not raw runes
	second := strings.NewReplacer(`"L1"`, `"L2"`, "stalled-documents@1", hostileRule,
		`"C1"`, `"D1"`, `"C2"`, `"D2"`, `"C3"`, `"D3"`, `"C4"`, `"D4"`, `"C5"`, `"D5"`).Replace(text[start:end])

	_, stderr, err := runDraft(t, text[:end]+",\n"+second+text[end:], "-")

	require.NoError(t, err)
	lines := strings.Split(strings.TrimRight(stderr, "\n"), "\n")
	require.Len(t, lines, 1, "one ignored lead, one line: %q", stderr)
	assert.True(t, strings.HasPrefix(lines[0], "not drafted: the lead for rule other@1"))
	for _, r := range stderr {
		assert.False(t, r != '\n' && (r < 0x20 || (r >= 0x7f && r <= 0x9f) || (r >= 0x202a && r <= 0x202e)), "U+%04X on stderr", r)
	}
}
