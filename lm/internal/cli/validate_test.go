package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spf13/cobra"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/output"
)

func TestValidate_validManifest_returnsNoError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/validator/validate" {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(client.ValidationResult{})
	}))
	defer srv.Close()

	c := client.New(config.Context{Server: srv.URL})
	result, err := c.ValidateManifest([]byte("apiVersion: connectors.lumnik.io/v1\n"))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	out := &bytes.Buffer{}
	if err := output.FormatValidation(out, "test.yaml", rerunCommand("test.yaml"), result); err != nil {
		t.Fatalf("expected nil, got %v", err)
	}
	if !strings.Contains(out.String(), "valid") {
		t.Errorf("expected 'valid' in output, got: %s", out.String())
	}
}

func TestValidate_invalidManifest_returnsErrorAndPrintsPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(client.ValidationResult{
			Errors: []client.ValidationError{{
				Path: "spec.auth.token_env", Severity: "ERROR",
				Message: "required field missing", Recommendations: []string{"set it"},
			}},
		})
	}))
	defer srv.Close()

	c := client.New(config.Context{Server: srv.URL})
	result, err := c.ValidateManifest([]byte("dummy"))
	if err != nil {
		t.Fatalf("ValidateManifest error: %v", err)
	}
	out := &bytes.Buffer{}
	err = output.FormatValidation(out, "bad.yaml", rerunCommand("bad.yaml"), result)
	if err == nil {
		t.Fatal("expected non-nil error from formatter")
	}
	if !strings.Contains(out.String(), "spec.auth.token_env") {
		t.Errorf("missing path in output, got: %s", out.String())
	}
}

func TestValidate_fileNotFound_errors(t *testing.T) {
	dir := t.TempDir()
	missing := filepath.Join(dir, "does-not-exist.yaml")
	if _, err := os.Stat(missing); err == nil {
		t.Fatal("setup error: file should not exist")
	}
	_, err := readManifest(&cobra.Command{Use: "x"}, missing)
	if err == nil {
		t.Fatal("expected error for missing file")
	}
}
