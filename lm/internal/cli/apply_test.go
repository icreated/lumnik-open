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
	"github.com/spf13/cobra"
)

func TestApplyCmd_loadsYaml(t *testing.T) {
	dir := t.TempDir()
	yaml := []byte("apiVersion: connectors.lumnik.io/v1\nkind: RestConnector\nmetadata:\n  name: test\n  tenant: 1\nspec:\n  base_url: https://api.example.com\n  auth: { kind: bearer, token_env: TOKEN }\n  endpoints: []\n")
	path := filepath.Join(dir, "stripe.yaml")
	if err := os.WriteFile(path, yaml, 0644); err != nil {
		t.Fatal(err)
	}
	body, err := readManifest(&cobra.Command{Use: "x"}, path)
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
	if err := output.FormatValidation(out, "x.yaml", rerunCommand("x.yaml"), &vr); err == nil {
		t.Fatal("expected error from formatter")
	}
	if !strings.Contains(out.String(), "spec.auth.token_env") {
		t.Errorf("expected formatted validation in output, got: %s", out.String())
	}
}

// A manifest the hub accepted can still carry advice — and the one that matters most, "no scopes
// declared", decides whether anything this connector ingests is askable at all. The hub now sends
// its warnings in the success body (#531); apply renders them the way validate does.
//
// Stdout stays the JSON body alone: it is what the connector docs print and what a
// `lm apply -f x.yaml | jq` in an integrator's script reads. The advice goes to stderr, where a
// diagnostic belongs, and the exit code stays 0 — a warning has never failed a validate and a CI
// gate built on that must not start failing here.
func TestApply_successWithWarnings_rendersThemOnStderr_exitZero(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"orders","connectorId":"487ed9d1-58d5-4120-969c-dc448910e1f2",` +
			`"endpointsApplied":1,"warnings":[{"path":"metadata.scopes","severity":"WARNING",` +
			`"message":"no scopes declared — the connector's tables will not be askable in any métier",` +
			`"recommendations":["Add: scopes: [compta]"]}]}`))
	}))
	defer srv.Close()

	path := filepath.Join(t.TempDir(), "orders.yaml")
	if err := writeFile(t, path, "kind: Connector\n"); err != nil {
		t.Fatal(err)
	}
	out, errOut, err := runLm(t, srv, "apply", "-f", path)
	if err != nil {
		t.Fatalf("a warning must not fail apply: %v", err)
	}

	var body map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &body); err != nil {
		t.Errorf("stdout must stay parseable JSON — a pipe reads it: %s", out)
	}
	if body["connectorId"] != "487ed9d1-58d5-4120-969c-dc448910e1f2" {
		t.Errorf("stdout lost the apply body: %s", out)
	}
	for _, want := range []string{"orders.yaml", "1 warning(s)", "WARN", "metadata.scopes",
		"not be askable", "Add: scopes: [compta]"} {
		if !strings.Contains(errOut, want) {
			t.Errorf("stderr missing %q, got: %s", want, errOut)
		}
	}
}

// The quiet path: no warnings, no report — the command prints the one JSON line it always did.
func TestApply_successWithoutWarnings_printsNothingExtra(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"orders","connectorId":"x","endpointsApplied":1}`))
	}))
	defer srv.Close()

	path := filepath.Join(t.TempDir(), "orders.yaml")
	if err := writeFile(t, path, "kind: Connector\n"); err != nil {
		t.Fatal(err)
	}
	out, errOut, err := runLm(t, srv, "apply", "-f", path)
	if err != nil {
		t.Fatal(err)
	}
	if errOut != "" {
		t.Errorf("nothing to advise, nothing on stderr, got: %s", errOut)
	}
	if out != `{"name":"orders","connectorId":"x","endpointsApplied":1}`+"\n" {
		t.Errorf("stdout changed shape: %q", out)
	}
}

// The generator door applies too, and its --scope is OPTIONAL: `lm source rest … --apply` can mint
// exactly the scopeless connector the flagship advisory is about. It printed a summary parsed down
// to connectorId/endpointsApplied and said nothing else — the same drop, one door over (#531).
func TestSourceRestApply_rendersWarningsOnStderr(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/platform/sources/rest/from-openapi", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"manifest":"kind: Connector\n"}`))
	})
	mux.HandleFunc("/api/platform/connectors/apply", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"acme","connectorId":"c1","endpointsApplied":2,` +
			`"warnings":[{"path":"metadata.scopes","severity":"WARNING",` +
			`"message":"no scopes declared","recommendations":["Add: scopes: [compta]"]}]}`))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	_, errOut, err := runLm(t, srv, "source", "rest", "from-openapi", "https://demo/spec",
		"--name", "acme", "--apply")
	if err != nil {
		t.Fatalf("a warning must not fail the generator door: %v", err)
	}
	if !strings.Contains(errOut, "metadata.scopes") || !strings.Contains(errOut, "WARN") {
		t.Errorf("the advisory must reach this door too, stderr was: %s", errOut)
	}
}
