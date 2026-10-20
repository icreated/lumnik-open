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

	for _, verb := range []string{"list", "get", "apply", "generate", "rm"} {
		assert.Contains(t, out.String(), verb)
	}
}

func TestViewRmCmd_requiresNameArg(t *testing.T) {
	cmd := newViewCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs([]string{"rm"})
	err := cmd.Execute()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "accepts 1 arg")
}

func TestViewRmCmd_e2e_deletesAndConfirms(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete || r.URL.Path != "/api/views/gold-desk" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"view", "rm", "gold-desk"})
	require.NoError(t, cmd.Execute())
	assert.Contains(t, buf.String(), "gold-desk")
}

// #1050: a link whose target does not exist yet is applied with a warning; the integrator must see it
// (stderr, exit 0 — a warning has never failed an apply), and stdout stays the one confirmation line.
func TestViewApplyCmd_e2e_printsLinkWarningsOnStderrAndStillSucceeds(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"categories","scope":"catalogue","warnings":[{"path":"spec.links[0].targetView",`+
			`"severity":"WARNING","message":"targetView 'articles' is not a view of scope 'catalogue' (yet)","recommendations":[]}]}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	f := filepath.Join(t.TempDir(), "categories.yaml")
	require.NoError(t, os.WriteFile(f, []byte("kind: View\n"), 0o600))

	cmd := NewRootCmd()
	out, errOut := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(errOut)
	cmd.SetArgs([]string{"view", "apply", "-f", f})
	require.NoError(t, cmd.Execute())
	assert.Equal(t, "applied: categories (scope catalogue)\n", out.String())
	assert.Contains(t, errOut.String(), "spec.links[0].targetView")
	assert.Contains(t, errOut.String(), "articles")
}

// `lm view NAME [KEY]` renders a saved view in one gesture, as :views → Enter does: the stored
// manifest is fetched and posted to the same render/detail doors -f uses (#1176). It took
// `lm view get NAME | lm view -f -` before.
func TestViewCmd_rendersASavedViewByName(t *testing.T) {
	const stored = "kind: View\nmetadata:\n  name: gold-desk\n"
	var posted []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/views/gold-desk":
			_, _ = fmt.Fprint(w, stored)
		case r.Method == http.MethodPost && r.URL.Path == "/api/views/render":
			b := new(bytes.Buffer)
			_, _ = b.ReadFrom(r.Body)
			posted = append(posted, "render:"+b.String())
			_, _ = fmt.Fprint(w, `{"title":"Gold desk","columns":[{"name":"code","label":"Code"}],"rows":[{"code":"C-1"}]}`)
		case r.Method == http.MethodPost && r.URL.Path == "/api/views/detail":
			b := new(bytes.Buffer)
			_, _ = b.ReadFrom(r.Body)
			posted = append(posted, "detail:"+r.URL.Query().Get("key")+":"+b.String())
			_, _ = fmt.Fprint(w, `{"title":"Gold desk","key":"C-1","fields":[{"name":"code","label":"Code","value":"C-1"}]}`)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)
	out := runRoot(t, "view", "gold-desk")
	assert.Contains(t, out, "Gold desk")
	assert.Contains(t, out, "C-1")
	runRoot(t, "view", "gold-desk", "C-1")
	assert.Equal(t, []string{"render:" + stored, "detail:C-1:" + stored}, posted)

	for _, args := range [][]string{{"view"}, {"view", "gold-desk", "C-1", "extra"}} {
		cmd := NewRootCmd()
		cmd.SetOut(new(bytes.Buffer))
		cmd.SetErr(new(bytes.Buffer))
		cmd.SetArgs(args)
		err := cmd.Execute()
		require.Error(t, err, "%v", args)
		if len(args) == 1 {
			assert.Contains(t, err.Error(), "-f")
		}
	}
}
