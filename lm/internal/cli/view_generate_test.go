package cli

import (
	"bytes"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestViewGenerateCmd_requiresScopeAndDescription(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"generate", "clients"})
	err := cmd.Execute()

	// One arg only → cobra arg validation must fire (not client resolution).
	require.Error(t, err)
	assert.Contains(t, err.Error(), "arg")
}

func TestViewGenerateCmd_isWiredUnderView(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"generate", "clients", "les clients gold", "-o", "x.yaml"})
	err := cmd.Execute()

	// With no context configured it fails resolving the client, NOT on arg/flag parsing —
	// proving the subcommand, its positionals, and -o are wired.
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "unknown command")
	assert.NotContains(t, err.Error(), "unknown flag")
	assert.NotContains(t, err.Error(), "accepts")
}

func TestViewGenerateCmd_oShorthandSurvivesTheRootPersistentOutputFlag(t *testing.T) {
	// The root command carries a persistent -o/--output (format). generate shadows it with a
	// SAME-NAME local flag (the source_rest probe precedent) — a different-name flag sharing
	// the shorthand panics at cobra's flag merge. Caught live, invisible to newViewCmd-only
	// tests: this one goes through the real root.
	root := NewRootCmd()
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	root.SetArgs([]string{"view", "generate", "clients", "les clients", "-o", "x.yaml"})
	err := root.Execute()

	// No context configured → it must FAIL (client resolution), never panic or reject the flag.
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "unknown flag")
	assert.NotContains(t, err.Error(), "shorthand")
}

func TestRewriteSuggestedFile_pointsAtTheUsersFile(t *testing.T) {
	in := "# lm view -f clients_gold.yaml --filter 'segment=GOLD'\napiVersion: apps.lumnik.io/v1\n"
	got := rewriteSuggestedFile(in, "gold.yaml")
	assert.Equal(t, "# lm view -f gold.yaml --filter 'segment=GOLD'\napiVersion: apps.lumnik.io/v1\n", got)
}

func TestRewriteSuggestedFile_commentWithoutFilters_tokenEndsAtNewline(t *testing.T) {
	in := "# lm view -f clients_gold.yaml\napiVersion: apps.lumnik.io/v1\n"
	got := rewriteSuggestedFile(in, "gold.yaml")
	assert.Equal(t, "# lm view -f gold.yaml\napiVersion: apps.lumnik.io/v1\n", got)
}

// The hub writes the asked-for and kept lines BEFORE the render command (#1513): the rewrite must
// find the command wherever it sits in the leading comment block.
func TestRewriteSuggestedFile_afterTheHubsAskedForAndKeptLines(t *testing.T) {
	in := "# asked for: les clients gold avec leur ville et segment\n" +
		"# kept: segment=GOLD — a condition of that description not listed here is NOT enforced\n" +
		"# lm view -f gold_customers.yaml --filter 'segment=GOLD'\n" +
		"apiVersion: \"apps.lumnik.io/v1\"\n"
	got := rewriteSuggestedFile(in, "gold.yaml")
	assert.Equal(t, "# asked for: les clients gold avec leur ville et segment\n"+
		"# kept: segment=GOLD — a condition of that description not listed here is NOT enforced\n"+
		"# lm view -f gold.yaml --filter 'segment=GOLD'\n"+
		"apiVersion: \"apps.lumnik.io/v1\"\n", got)
}

// A description that itself mentions the command never moves: only a line of its own counts, and
// only in the leading comment block.
func TestRewriteSuggestedFile_onlyInTheLeadingCommentBlock(t *testing.T) {
	in := "apiVersion: apps.lumnik.io/v1\n# lm view -f other.yaml\n"
	assert.Equal(t, in, rewriteSuggestedFile(in, "gold.yaml"))
}

func TestRewriteSuggestedFile_noCommentLine_unchanged(t *testing.T) {
	in := "apiVersion: apps.lumnik.io/v1\nkind: View\n"
	assert.Equal(t, in, rewriteSuggestedFile(in, "gold.yaml"))
}

func TestRewriteSuggestedFile_quotesAnAwkwardOutFile(t *testing.T) {
	in := "# lm view -f clients_gold.yaml --filter 'segment=GOLD'\napiVersion: apps.lumnik.io/v1\n"
	got := rewriteSuggestedFile(in, "my file.yaml")
	assert.Equal(t, "# lm view -f 'my file.yaml' --filter 'segment=GOLD'\napiVersion: apps.lumnik.io/v1\n", got)
}

func TestPosixQuote(t *testing.T) {
	assert.Equal(t, "gold.yaml", posixQuote("gold.yaml"))
	assert.Equal(t, "views/gold_2.yaml", posixQuote("views/gold_2.yaml"))
	assert.Equal(t, "'my file.yaml'", posixQuote("my file.yaml"))
	assert.Equal(t, `'o'\''brien.yaml'`, posixQuote("o'brien.yaml"))
}

func TestConfirmLine_quotesAnAwkwardFilenameInTheCommand(t *testing.T) {
	assert.Equal(t,
		"manifest written to my file.yaml — review it, then: lm view -f 'my file.yaml'\n",
		confirmLine("my file.yaml"))
	assert.Equal(t,
		"manifest written to gold.yaml — review it, then: lm view -f gold.yaml\n",
		confirmLine("gold.yaml"))
}

func TestViewGenerateCmd_refusesALineBreakInTheOutFile(t *testing.T) {
	// A filename with a line break would split the rewritten comment into raw YAML above the
	// manifest (single quotes don't stop a YAML comment at end-of-line) — refused at the
	// threshold. YAML 1.1 also breaks on NEL/LS/PS, so those count too.
	for _, bad := range []string{"bad\nname.yaml", "bad\rname.yaml",
		"bad\u0085name.yaml", "bad\u2028name.yaml", "bad\u2029name.yaml"} {
		cmd := newViewCmd()
		var out bytes.Buffer
		cmd.SetOut(&out)
		cmd.SetErr(&out)
		cmd.SetArgs([]string{"generate", "clients", "les clients", "-o", bad})
		err := cmd.Execute()

		require.Error(t, err, "outFile %q", bad)
		assert.Contains(t, err.Error(), "line break", "outFile %q", bad)
	}
}
