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
// Stdout stays the one applied line (the hub's body under -o json, what a script pipes into jq).
// The advice goes to stderr, where a diagnostic belongs, and the exit code stays 0 — a warning has
// never failed a validate and a CI gate built on that must not start failing here.
func TestApply_successWithWarnings_rendersThemOnStderr_exitZero(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"orders","connectorId":"487ed9d1-58d5-4120-969c-dc448910e1f2",` +
			`"endpointsApplied":1,"warnings":[{"path":"metadata.scopes","severity":"WARNING",` +
			`"message":"no scopes declared — a user bound to a scope will not see this connector, its runs or its dead letters",` +
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

	if out != `Applied connector "orders" — 1 endpoint`+"\n" {
		t.Errorf("stdout must stay the applied line alone: %q", out)
	}
	for _, want := range []string{"orders.yaml", "1 warning(s)", "WARN", "metadata.scopes",
		"will not see this connector", "Add: scopes: [compta]"} {
		if !strings.Contains(errOut, want) {
			t.Errorf("stderr missing %q, got: %s", want, errOut)
		}
	}
}

// The quiet path: no warnings, no report — one line saying what was applied.
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
	if out != `Applied connector "orders" — 1 endpoint`+"\n" {
		t.Errorf("stdout changed shape: %q", out)
	}
}

// A jdbc-generic connector applies one endpoint per table: the count is a plural.
func TestApply_countsEveryEndpoint(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"erp","connectorId":"x","endpointsApplied":3}`))
	}))
	defer srv.Close()
	path := filepath.Join(t.TempDir(), "erp.yaml")
	if err := writeFile(t, path, "kind: Connector\n"); err != nil {
		t.Fatal(err)
	}
	out, _, err := runLm(t, srv, "apply", "-f", path)
	if err != nil {
		t.Fatal(err)
	}
	if out != `Applied connector "erp" — 3 endpoints`+"\n" {
		t.Errorf("got %q", out)
	}
}

// -o json and -o yaml print the hub's answer for a script: the id, the count, the warnings. The
// warnings still reach stderr too — a pipe must not swallow the advice.
func TestApply_machineFormats_printTheHubAnswer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"name":"orders","connectorId":"487ed9d1-58d5-4120-969c-dc448910e1f2",` +
			`"endpointsApplied":1,"warnings":[{"path":"metadata.scopes","severity":"WARNING",` +
			`"message":"no scopes declared"}]}`))
	}))
	defer srv.Close()
	path := filepath.Join(t.TempDir(), "orders.yaml")
	if err := writeFile(t, path, "kind: Connector\n"); err != nil {
		t.Fatal(err)
	}

	out, errOut, err := runLm(t, srv, "apply", "-f", path, "-o", "json")
	if err != nil {
		t.Fatal(err)
	}
	var body struct {
		Name             string `json:"name"`
		ConnectorID      string `json:"connectorId"`
		EndpointsApplied int    `json:"endpointsApplied"`
		Warnings         []struct {
			Path string `json:"path"`
		} `json:"warnings"`
	}
	if err := json.Unmarshal([]byte(out), &body); err != nil {
		t.Fatalf("-o json must be parseable JSON: %v\n%s", err, out)
	}
	if body.Name != "orders" || body.ConnectorID != "487ed9d1-58d5-4120-969c-dc448910e1f2" ||
		body.EndpointsApplied != 1 || len(body.Warnings) != 1 || body.Warnings[0].Path != "metadata.scopes" {
		t.Errorf("-o json lost part of the hub's answer: %s", out)
	}
	if !strings.Contains(errOut, "metadata.scopes") {
		t.Errorf("the warning must still reach stderr: %q", errOut)
	}

	out, _, err = runLm(t, srv, "apply", "-f", path, "-o", "yaml")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"name: orders\n", "connectorId: 487ed9d1-58d5-4120-969c-dc448910e1f2\n", "endpointsApplied: 1\n"} {
		if !strings.Contains(out, want) {
			t.Errorf("-o yaml missing %q:\n%s", want, out)
		}
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
