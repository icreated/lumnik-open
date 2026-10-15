package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestViewCmd_readsFileAndRequiresFlag(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "v.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: View\n"), 0o644))

	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"-f", f})
	err := cmd.Execute()

	// With no context configured it fails resolving the client, NOT on flag parsing or
	// a missing file — proving -f is wired and the manifest was read.
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "required flag")
	assert.NotContains(t, err.Error(), "no such file")
}

func TestViewCmd_positionalKeyIsAccepted(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "v.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: View\n"), 0o644))

	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"-f", f, "C-200"})
	err := cmd.Execute()

	// No context configured → fails resolving the client, NOT on arg parsing (a positional
	// key must be accepted) and NOT on a missing file.
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "accepts") // cobra "accepts at most 0 arg(s)"
	assert.NotContains(t, err.Error(), "unknown command")
	assert.NotContains(t, err.Error(), "no such file")
}

func TestViewCmd_keyWithFilterErrors(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "v.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: View\n"), 0o644))

	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"-f", f, "C-200", "--filter", "segment=GOLD"})
	err := cmd.Execute()

	// --filter with a detail KEY must fail fast with a clear message, not be silently ignored.
	require.Error(t, err)
	assert.Contains(t, err.Error(), "--filter")
}

func TestEmitDetail_jsonHonorsOutputFlag(t *testing.T) {
	res := &client.ViewDetailResult{
		Title: "Clients", Key: "C-200",
		Fields:         []client.DetailField{{Name: "name", Label: "Nom", Value: "Marie"}},
		ConflictLedger: true,
		Conflicts: []client.DetailConflict{{Column: "name", Label: "Nom",
			Sources: []client.DetailSource{{Src: "cust_siebel", Value: client.StrPtr("Marie")}, {Src: "t_cust_csv", Value: client.StrPtr("MARIE A.")}}}},
	}
	var b bytes.Buffer
	require.NoError(t, emitDetail(&b, "json", res))
	out := b.String()
	assert.Contains(t, out, `"conflictLedger": true`)
	assert.Contains(t, out, `"src": "cust_siebel"`)
	assert.NotContains(t, out, "divergent") // no human layout in json mode
}

func TestEmitDetail_tableUsesHumanLayout(t *testing.T) {
	res := &client.ViewDetailResult{
		Title: "Clients", Key: "C-200",
		Fields:         []client.DetailField{{Name: "name", Label: "Nom", Value: "Marie"}},
		ConflictLedger: true,
		Conflicts: []client.DetailConflict{{Column: "name", Label: "Nom",
			Sources: []client.DetailSource{{Src: "a", Value: client.StrPtr("x")}, {Src: "b", Value: client.StrPtr("y")}}}},
	}
	var b bytes.Buffer
	require.NoError(t, emitDetail(&b, "", res))
	assert.Contains(t, b.String(), "Ambiguïtés et divergences")
}

func TestEmitList_jsonHonorsOutputFlag(t *testing.T) {
	res := &client.ViewResult{
		Title:   "Clients",
		Columns: []client.ViewColumn{{Name: "name", Label: "Nom", PgType: "text"}},
		Rows:    [][]string{{"Marie"}},
	}
	var b bytes.Buffer
	require.NoError(t, emitList(&b, "json", res, nil))
	out := b.String()
	assert.Contains(t, out, `"columns"`)
	assert.Contains(t, out, `"rows"`)
	assert.Contains(t, out, "Marie")
}

func TestEmitList_tableUsesHumanLayout(t *testing.T) {
	res := &client.ViewResult{
		Title:   "Clients",
		Columns: []client.ViewColumn{{Name: "name", Label: "Nom", PgType: "text"}},
		Rows:    [][]string{{"Marie"}},
	}
	var b bytes.Buffer
	require.NoError(t, emitList(&b, "", res, nil))
	assert.Contains(t, b.String(), "Nom") // the header label appears in the table
}
