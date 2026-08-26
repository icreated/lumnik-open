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

func TestEntityApplyCmd_printsApplied(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/entities/apply" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, `{"name":"Customer","view":"connector.\"v_customer\""}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	f := filepath.Join(dir, "customer.yaml")
	if err := os.WriteFile(f, []byte("kind: Entity\nname: Customer\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "apply", "-f", f})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.Contains(buf.String(), "Customer") {
		t.Errorf("expected entity name in output, got: %s", buf.String())
	}
}

func TestEntityApplyCmd_serverError_returnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"error":"kind must be Entity"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	dir := t.TempDir()
	f := filepath.Join(dir, "bad.yaml")
	if err := os.WriteFile(f, []byte("kind: Connector\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"entity", "apply", "-f", f})
	if err := cmd.Execute(); err == nil {
		t.Fatal("expected error on 400, got nil")
	}
}
