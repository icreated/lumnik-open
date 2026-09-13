package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestProcessApplyCmd_requiresFileAndReadsIt(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "p.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Process\n"), 0o644))

	cmd := newProcessCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"apply", "-f", f})
	err := cmd.Execute()

	// no context configured → fails at client resolution, not flag parsing / file reading
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "required flag")
	assert.NotContains(t, err.Error(), "no such file")
}

func TestProcessMutatingCmds_takeExactlyOneName(t *testing.T) {
	// The three gestures a declared process never had (#487). Each needs a name and nothing
	// else: a missing one must be a usage error, not a call against the whole tenant.
	for _, verb := range []string{"rm", "enable", "disable"} {
		cmd := newProcessCmd()
		var out bytes.Buffer
		cmd.SetOut(&out)
		cmd.SetErr(&out)
		cmd.SetArgs([]string{verb})
		require.Error(t, cmd.Execute(), verb)
		assert.Contains(t, out.String(), "accepts 1 arg", verb)

		named := newProcessCmd()
		named.SetOut(&out)
		named.SetErr(&out)
		named.SetArgs([]string{verb, "gold-arrive"})
		// no context configured → it reaches client resolution, so the args were accepted
		err := named.Execute()
		require.Error(t, err, verb)
		assert.NotContains(t, err.Error(), "accepts 1 arg", verb)
	}
}
