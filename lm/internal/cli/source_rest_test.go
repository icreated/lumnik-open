package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSourceRestCmd_exists(t *testing.T) {
	// Placeholder so the file compiles. Full from-openapi/probe tests added in Task 11.
}

func TestFromOpenApiCmd_apply_chains_two_endpoints(t *testing.T) {
	var openApiCalled, applyCalled bool
	mux := http.NewServeMux()
	mux.HandleFunc("/api/platform/sources/rest/from-openapi", func(w http.ResponseWriter, r *http.Request) {
		openApiCalled = true
		w.Header().Set("Content-Type", "text/plain")
		_, _ = io.WriteString(w,
			"apiVersion: connectors.lumnik.io/v1\n"+
				"kind: RestConnector\n"+
				"metadata: { name: test, tenant: 1 }\n"+
				"spec: { base_url: x, auth: { kind: bearer, token_env: T }, endpoints: [] }\n")
	})
	mux.HandleFunc("/api/platform/connectors/apply", func(w http.ResponseWriter, r *http.Request) {
		applyCalled = true
		_, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"connectorId":      "00000000-0000-0000-0000-000000000001",
			"endpointsApplied": 0,
		})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()
	setupCfg(t, srv.URL)

	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"source", "rest", "from-openapi", "https://demo/spec", "--name", "test", "--apply"})
	out := &bytes.Buffer{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	if err := rootCmd.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}

	if !openApiCalled {
		t.Errorf("expected /from-openapi to be called")
	}
	if !applyCalled {
		t.Errorf("expected /apply to be called")
	}
}

func TestFromOpenApiCmd_apply_requires_name(t *testing.T) {
	// --name is MarkFlagRequired, so missing it always fails regardless of --apply
	rootCmd := NewRootCmd()
	rootCmd.SetArgs([]string{"source", "rest", "from-openapi", "https://demo/spec", "--apply"})
	out := &strings.Builder{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(out)
	err := rootCmd.Execute()
	if err == nil {
		t.Fatalf("expected error for missing --name")
	}
}

// The summary is the last thing a reader sees after `--apply`, and it used to send them to
// `lm endpoint enable`, which activates no scheduling: apply already enables every endpoint it
// writes, and the scheduler needs `enabled = true AND schedule IS NOT NULL`. The generated draft
// declares no schedule, so nothing is scheduled and `lm connector run` is the gesture that fetches.
func TestApplySummary_doesNotPromiseScheduling(t *testing.T) {
	var b bytes.Buffer
	printApplySummary(&b, []byte(`{"connectorId":"c-1","endpointsApplied":2}`), "stripe")
	out := b.String()

	if strings.Contains(out, "endpoint enable") {
		t.Errorf("summary sends the reader to `lm endpoint enable`, which starts nothing:\n%s", out)
	}
	if strings.Contains(out, "activate scheduling") {
		t.Errorf("summary claims the next gesture activates scheduling; it does not:\n%s", out)
	}
	if !strings.Contains(out, "lm connector run stripe") {
		t.Errorf("summary does not name the gesture that actually fetches:\n%s", out)
	}
}
