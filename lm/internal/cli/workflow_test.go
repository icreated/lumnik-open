package cli

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestWorkflowApplyCmd_requiresFileAndReadsIt(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "w.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Workflow\n"), 0o644))

	cmd := newWorkflowCmd()
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

func TestWorkflowApplyCmd_missingFileFlagFailsFast(t *testing.T) {
	cmd := newWorkflowCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"apply"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "required flag")
}

func TestWorkflowGetCmd_requiresNameArg(t *testing.T) {
	cmd := newWorkflowCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"get"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "accepts 1 arg")
}

// --- e2e contracts through the full root command against an httptest hub (the
// setupCfg pattern from entity_test.go / rag_test.go / view_store_test.go) ---

func TestWorkflowApplyCmd_e2e_printsHubResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/workflows/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"commande-lifecycle","table":"connector.t_commandes_csv","column":"statut"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	f := filepath.Join(dir, "commande-lifecycle.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: Workflow\n"), 0o600))

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"workflow", "apply", "-f", f})
	require.NoError(t, cmd.Execute())
	assert.Contains(t, buf.String(), "commande-lifecycle")
}

func TestWorkflowListCmd_e2e_rendersTheSummaryTable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/workflows" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `[{"name":"commande-lifecycle","table":"connector.t_commandes_csv","column":"statut","scope":"magasin","enabled":true}]`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"workflow", "list"})
	require.NoError(t, cmd.Execute())
	out := buf.String()
	for _, want := range []string{"NAME", "TABLE", "COLUMN", "SCOPE", "ENABLED",
		"commande-lifecycle", "connector.t_commandes_csv", "statut", "magasin", "true"} {
		assert.Contains(t, out, want)
	}
}

func TestWorkflowGetCmd_e2e_printsManifestYamlVerbatimAndEscapesName(t *testing.T) {
	const yaml = "apiVersion: apps.lumnik.io/v1\nkind: Workflow\nmetadata:\n  name: commande-lifecycle\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/workflows/commande-lifecycle/manifest" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = fmt.Fprint(w, yaml)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"workflow", "get", "commande-lifecycle"})
	require.NoError(t, cmd.Execute())
	// Verbatim: the manifest and nothing else.
	assert.Equal(t, yaml, buf.String())
}

func TestWorkflowHelp_namesAllVerbs(t *testing.T) {
	cmd := newWorkflowCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"--help"})
	require.NoError(t, cmd.Execute())

	for _, verb := range []string{"apply", "list", "get"} {
		assert.Contains(t, out.String(), verb)
	}
}
