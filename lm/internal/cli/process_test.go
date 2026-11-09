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

// The hub answers a Process or Entity apply with plain-text warnings while an index is still being built
// (#1838). They go to stderr — stdout under -o json is the body a script pipes into jq — and the exit stays 0.
func TestProcessApply_printsTheHubsWarningsOnStderr(t *testing.T) {
	const warning = "managed index on connector.t_acme_desk_erp is pending; Entity Process reads may be slower until it is ready"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/processes/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(`{"name":"lyon-customer","table":"connector.v_acmecustomer","emit":"decision.lyon","warnings":["` + warning + `"]}`))
	}))
	defer srv.Close()
	f := filepath.Join(t.TempDir(), "p.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Process\n"), 0o600))

	out, errOut, err := runLm(t, srv, "process", "apply", "-f", f)
	require.NoError(t, err)
	assert.Equal(t, `Applied process "lyon-customer" → table connector.v_acmecustomer, emits decision.lyon`+"\n", out)
	assert.Equal(t, "warning: "+warning+"\n", errOut)

	jsonOut, jsonErr, err := runLm(t, srv, "process", "apply", "-f", f, "-o", "json")
	require.NoError(t, err)
	assert.Contains(t, jsonOut, `"warnings"`)
	assert.Empty(t, jsonErr, "under -o json the warnings are in the body, not said twice")
}

func TestEntityApply_printsTheHubsWarningsOnStderr(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/entities/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(`{"name":"AcmeCustomer","view":"connector.\"v_acmecustomer\"","warnings":["managed index on connector.t_x is pending; Entity Process reads may be slower until it is ready"]}`))
	}))
	defer srv.Close()
	f := filepath.Join(t.TempDir(), "e.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Entity\n"), 0o600))

	out, errOut, err := runLm(t, srv, "entity", "apply", "-f", f)
	require.NoError(t, err)
	assert.Contains(t, out, `Applied entity "AcmeCustomer"`)
	assert.Equal(t, "warning: managed index on connector.t_x is pending; Entity Process reads may be slower until it is ready\n", errOut)

	// -o json keeps the hub's body for a script, warnings included, and says nothing twice
	jsonOut, jsonErr, err := runLm(t, srv, "entity", "apply", "-f", f, "-o", "json")
	require.NoError(t, err)
	assert.Contains(t, jsonOut, `"warnings"`)
	assert.Empty(t, jsonErr)
}
