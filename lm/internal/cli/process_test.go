package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// lm process apply says what it applied in one line, as every apply does; -o json keeps the hub's
// answer for a script.
func TestProcessApply_printsOneLine_andTheHubAnswerUnderJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/processes/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(`{"name":"litige-ouvert","table":"connector.t_acme_desk_orders","emit":"decision.litige-ouvert"}`))
	}))
	defer srv.Close()
	f := filepath.Join(t.TempDir(), "p.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Process\n"), 0o600))

	out, _, err := runLm(t, srv, "process", "apply", "-f", f)
	require.NoError(t, err)
	assert.Equal(t, `Applied process "litige-ouvert" → table connector.t_acme_desk_orders, emits decision.litige-ouvert`+"\n", out)

	out, _, err = runLm(t, srv, "process", "apply", "-f", f, "-o", "json")
	require.NoError(t, err)
	var body map[string]string
	require.NoError(t, json.Unmarshal([]byte(out), &body), out)
	assert.Equal(t, map[string]string{"name": "litige-ouvert", "table": "connector.t_acme_desk_orders",
		"emit": "decision.litige-ouvert"}, body)
}

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
