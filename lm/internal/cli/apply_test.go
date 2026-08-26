package cli

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/output"
)

func TestApplyCmd_loadsYaml(t *testing.T) {
	dir := t.TempDir()
	yaml := []byte("apiVersion: connectors.lumnik.io/v1\nkind: RestConnector\nmetadata:\n  name: test\n  tenant: 1\nspec:\n  base_url: https://api.example.com\n  auth: { kind: bearer, token_env: TOKEN }\n  endpoints: []\n")
	path := filepath.Join(dir, "stripe.yaml")
	if err := os.WriteFile(path, yaml, 0644); err != nil {
		t.Fatal(err)
	}
	body, err := readYamlFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(body) == 0 {
		t.Error("empty body")
	}
}

func TestApply_serverReturns400_rendersValidationErrors(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(400)
		_ = json.NewEncoder(w).Encode(client.ValidationResult{
			Errors: []client.ValidationError{{
				Path: "spec.auth.token_env", Severity: "ERROR",
				Message: "required field missing",
			}},
		})
	}))
	defer srv.Close()

	c := client.New(config.Context{Server: srv.URL})
	_, err := c.ApplyManifest([]byte("dummy"))
	if err == nil {
		t.Fatal("expected error on 400")
	}
	var apiErr *client.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 400 {
		t.Fatalf("expected APIError(400), got %T %v", err, err)
	}
	var vr client.ValidationResult
	if err := json.Unmarshal([]byte(apiErr.Body), &vr); err != nil {
		t.Fatalf("expected parseable ValidationResult, got: %v", err)
	}
	out := &bytes.Buffer{}
	if err := output.FormatValidation(out, "x.yaml", &vr); err == nil {
		t.Fatal("expected error from formatter")
	}
	if !strings.Contains(out.String(), "spec.auth.token_env") {
		t.Errorf("expected formatted validation in output, got: %s", out.String())
	}
}
