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

func TestViewListCmd_scopeFlagIsAccepted(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"list", "--scope", "clients"})
	err := cmd.Execute()

	// No context configured → fails resolving the client, not on flag parsing.
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "unknown flag")
	assert.NotContains(t, err.Error(), "unknown command")
}

func TestViewListCmd_noScopeStillReachesClient(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"list"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.NotContains(t, err.Error(), "unknown command")
}

func TestViewGetCmd_requiresNameArg(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"get"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "accepts 1 arg")
}

func TestViewGetCmd_outputFlagIsAcceptedAndShadowsFormat(t *testing.T) {
	dir := t.TempDir()
	out := filepath.Join(dir, "gold-desk.yaml")

	cmd := newViewCmd()
	var buf bytes.Buffer
	cmd.SetOut(&buf)
	cmd.SetErr(&buf)
	cmd.SetArgs([]string{"get", "gold-desk", "-o", out})
	err := cmd.Execute()

	// No context configured → fails resolving the client, not on flag parsing, and the
	// file must not have been written (client never ran).
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "unknown flag")
	_, statErr := os.Stat(out)
	assert.True(t, os.IsNotExist(statErr))
}

func TestViewApplyCmd_requiresFileAndReadsIt(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "v.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: View\n"), 0o644))

	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"apply", "-f", f})
	err := cmd.Execute()

	// No context configured → fails at client resolution, not flag parsing / file reading.
	require.Error(t, err)
	assert.NotContains(t, err.Error(), "required flag")
	assert.NotContains(t, err.Error(), "no such file")
}

func TestViewApplyCmd_missingFileFlagFailsFast(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"apply"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "required flag")
}

// --- e2e contracts through the full root command against an httptest hub (the
// setupCfg pattern from entity_test.go / rag_test.go) ---

func TestViewApplyCmd_e2e_printsAppliedNameAndScope(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/views/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"gold-desk","scope":"clients"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	f := filepath.Join(dir, "gold-desk.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: View\n"), 0o600))

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"view", "apply", "-f", f})
	require.NoError(t, cmd.Execute())
	assert.Equal(t, "applied: gold-desk (scope clients)\n", buf.String())
}

func TestViewApplyCmd_e2e_scopeRefusalSurfacesHonestError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		_, _ = fmt.Fprint(w, `{"error":"scope not granted"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	f := filepath.Join(dir, "v.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: View\n"), 0o600))

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"view", "apply", "-f", f})
	err := cmd.Execute()
	require.Error(t, err)
	assert.Equal(t, "access refused (403): scope not granted", err.Error())
}

func TestViewListCmd_e2e_rendersTheSummaryTable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/views" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `[{"name":"a","scope":"s","title":"T"}]`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"view", "list"})
	require.NoError(t, cmd.Execute())
	out := buf.String()
	for _, want := range []string{"NAME", "SCOPE", "TITLE", "a", "s", "T"} {
		assert.Contains(t, out, want)
	}
}

func TestViewGetCmd_e2e_printsManifestYamlVerbatim(t *testing.T) {
	const yaml = "apiVersion: apps.lumnik.io/v1\nkind: View\nmetadata:\n  name: gold-desk\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/views/gold-desk" {
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
	cmd.SetArgs([]string{"view", "get", "gold-desk"})
	require.NoError(t, cmd.Execute())
	// Verbatim: the manifest and nothing else.
	assert.Equal(t, yaml, buf.String())
}

func TestViewHelp_namesAllVerbs(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"--help"})
	require.NoError(t, cmd.Execute())

	for _, verb := range []string{"list", "get", "apply", "generate"} {
		assert.Contains(t, out.String(), verb)
	}
}
