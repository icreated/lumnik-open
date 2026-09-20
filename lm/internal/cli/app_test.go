package cli

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAppApplyCmd_postsToTheAppEndpoint(t *testing.T) {
	var gotPath, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		b := new(bytes.Buffer)
		_, _ = b.ReadFrom(r.Body)
		gotBody = b.String()
		if r.Method != http.MethodPost || r.URL.Path != "/api/apps/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"lumilait","scope":"lumilait","default":false}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	f := filepath.Join(t.TempDir(), "lumilait.yaml")
	manifest := "kind: App\nmetadata:\n  name: lumilait\nspec:\n  title: Lumilait\n  scope: lumilait\n  icon: generic\n"
	if err := os.WriteFile(f, []byte(manifest), 0o600); err != nil {
		t.Fatal(err)
	}

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "apply", "-f", f})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// The routing is the point: a kind:App manifest handed to `lm apply -f` lands at the
	// CONNECTOR endpoint, silently. Assert where it went, not only what was printed.
	if gotPath != "/api/apps/apply" {
		t.Errorf("posted to %q, want /api/apps/apply", gotPath)
	}
	if gotBody != manifest {
		t.Errorf("body was rewritten in flight:\n%q", gotBody)
	}
	if !strings.Contains(buf.String(), "lumilait") {
		t.Errorf("expected the app name in output, got: %s", buf.String())
	}
}

func TestAppApplyCmd_serverError_returnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"error":"kind must be 'App'"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	f := filepath.Join(t.TempDir(), "bad.yaml")
	if err := os.WriteFile(f, []byte("kind: Connector\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "apply", "-f", f})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected error on 400, got nil")
	}
}

func TestAppRmCmd_printsDeleted(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete || r.URL.Path != "/api/apps/lumilait" {
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
	cmd.SetArgs([]string{"app", "rm", "lumilait"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.Contains(buf.String(), "lumilait") {
		t.Errorf("expected the app name in output, got: %s", buf.String())
	}
}

func TestAppRmCmd_serverError_returnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"error":"cannot remove the last default app — apply a different app with default: true first, which demotes this one automatically"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "rm", "clients"})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected error on 400, got nil")
	}
}

func TestAppApplyCmd_requiresAFile(t *testing.T) {
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"app", "apply"})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected -f to be required")
	}
}
