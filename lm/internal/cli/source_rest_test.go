package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"
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

// fakeCID is the connector every fake in this file serves; the CLI resolves a connector by name
// before anything else, so each server needs the listing route.
const fakeCID = "00000000-0000-0000-0000-0000000000c1"

// connectorsRoute registers the listing the CLI resolves against. One spelling, because the
// file's own history says it: a fake that drifts from the real route is how a command that had
// never once worked kept a green test.
func connectorsRoute(mux *http.ServeMux, typeID string) {
	mux.HandleFunc("/api/platform/connectors", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": fakeCID, "name": "acme", "typeId": typeID}})
	})
}

// runLm drives the real root command against a fake hub and returns both streams.
func runLm(t *testing.T, srv *httptest.Server, args ...string) (string, string, error) {
	t.Helper()
	setupCfg(t, srv.URL)
	rootCmd := NewRootCmd()
	rootCmd.SetArgs(args)
	out, errOut := &bytes.Buffer{}, &bytes.Buffer{}
	rootCmd.SetOut(out)
	rootCmd.SetErr(errOut)
	// Executed on its own line: a `return out.String(), …, rootCmd.Execute()` evaluates the
	// buffers BEFORE the command that fills them, and reports empty output for a command that
	// printed.
	err := rootCmd.Execute()
	return out.String(), errOut.String(), err
}

// preflightServer stands in for the hub's endpoint routes. It refuses a non-UUID endpoint id the
// way the real route does — @PathParam("eid") UUID never binds, so RESTEasy answers 404 before the
// method runs. Faking that refusal is the point: the previous fake matched any path ending in
// "/test" and so happily served the "*" the CLI had been sending since the first commit, which is
// how a command that had never once worked kept a green test.
func preflightServer(t *testing.T, endpoints []map[string]any,
	verdict func(eid string) map[string]any) (*httptest.Server, *[]string) {
	t.Helper()
	const cid = fakeCID
	var tested []string

	mux := http.NewServeMux()
	connectorsRoute(mux, restConnectorType)
	mux.HandleFunc("/api/platform/connectors/"+cid+"/endpoints", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(endpoints)
	})
	mux.HandleFunc("/api/platform/connectors/"+cid+"/endpoints/", func(w http.ResponseWriter, r *http.Request) {
		eid := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path,
			"/api/platform/connectors/"+cid+"/endpoints/"), "/test")
		if _, err := uuid.Parse(eid); err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		tested = append(tested, eid)
		_ = json.NewEncoder(w).Encode(verdict(eid))
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, &tested
}

func runRestTest(t *testing.T, srv *httptest.Server) (string, string, error) {
	t.Helper()
	return runLm(t, srv, "source", "rest", "test", "acme")
}

func TestSourceRestTest_walksEveryEnabledEndpoint(t *testing.T) {
	const a = "00000000-0000-0000-0000-0000000000a1"
	const b = "00000000-0000-0000-0000-0000000000b2"
	srv, tested := preflightServer(t,
		[]map[string]any{
			{"id": a, "name": "partners", "method": "GET", "enabled": true},
			{"id": b, "name": "orders", "method": "GET", "enabled": true},
		},
		func(eid string) map[string]any {
			return map[string]any{"endpoint": "e", "method": "GET", "status": 200, "ok": true, "items": 7}
		})

	out, _, err := runRestTest(t, srv)
	if err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out)
	}
	if len(*tested) != 2 {
		t.Fatalf("expected both endpoints pre-flighted, got %v", *tested)
	}
}

func TestSourceRestTest_skipsDisabledEndpointsAndSaysSo(t *testing.T) {
	const a = "00000000-0000-0000-0000-0000000000a1"
	const b = "00000000-0000-0000-0000-0000000000b2"
	srv, tested := preflightServer(t,
		[]map[string]any{
			{"id": a, "name": "partners", "method": "GET", "enabled": true},
			{"id": b, "name": "orders", "method": "GET", "enabled": false},
		},
		func(eid string) map[string]any {
			return map[string]any{"endpoint": "partners", "method": "GET", "status": 200, "ok": true, "items": 7}
		})

	_, errOut, err := runRestTest(t, srv)
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if len(*tested) != 1 || (*tested)[0] != a {
		t.Fatalf("expected only the enabled endpoint pre-flighted, got %v", *tested)
	}
	if !strings.Contains(errOut, "skipped 1 disabled") {
		t.Errorf("expected the skip to be announced, stderr=%q", errOut)
	}
}

// A pre-flight exists to be believed before a run is launched. Printing the refusal and exiting 0
// is the same lie one layer up as the dead source that used to report a Completed run.
func TestSourceRestTest_failsWhenAnEndpointDoesNotAnswer(t *testing.T) {
	const a = "00000000-0000-0000-0000-0000000000a1"
	srv, _ := preflightServer(t,
		[]map[string]any{{"id": a, "name": "partners", "method": "GET", "enabled": true}},
		func(eid string) map[string]any {
			return map[string]any{"endpoint": "partners", "method": "GET", "status": 401,
				"ok": false, "items": 0, "detail": "HTTP 401"}
		})

	out, _, err := runRestTest(t, srv)
	if err == nil {
		t.Fatalf("expected a non-zero exit when an endpoint refuses, out=%s", out)
	}
	if !strings.Contains(out, "401") {
		t.Errorf("expected the status on stdout, got %q", out)
	}
}

func TestSourceRestTest_refusesAConnectorWithNothingEnabled(t *testing.T) {
	srv, tested := preflightServer(t,
		[]map[string]any{{"id": "00000000-0000-0000-0000-0000000000a1", "name": "partners", "enabled": false}},
		func(eid string) map[string]any { return nil })

	_, _, err := runRestTest(t, srv)
	if err == nil {
		t.Fatal("expected an error: nothing was pre-flighted, so nothing may be reported as fine")
	}
	if len(*tested) != 0 {
		t.Fatalf("nothing should have been tested, got %v", *tested)
	}
}

// The command lives under `source rest`, so a connector of another family is refused by name. It
// used to reach the request builder and fail once per table with "Unknown pagination kind: null" —
// true, and useless: the word jdbc never appeared.
func TestSourceRestTest_refusesAConnectorOfAnotherFamily(t *testing.T) {
	mux := http.NewServeMux()
	connectorsRoute(mux, "jdbc-generic")
	srv := httptest.NewServer(mux)
	defer srv.Close()

	_, _, err := runRestTest(t, srv)
	if err == nil {
		t.Fatal("expected a jdbc connector to be refused")
	}
	if !strings.Contains(err.Error(), "jdbc-generic") || !strings.Contains(err.Error(), "rest-generic") {
		t.Errorf("the refusal must name both families, got %q", err)
	}
}

// probeServer stands in for the hub's discovery route, refusing the way RESTEasy does: the route
// is @Produces(TEXT_PLAIN), so an `Accept: application/json` request is answered 406 before the
// method runs. Faking that refusal is the point — the client used to go through the JSON POST
// helper, so `lm source rest probe` stopped at "API error 406 (no details from the server)" on
// every invocation and never reached its own logic.
func probeServer(t *testing.T, typeID string) (*httptest.Server, *string) {
	t.Helper()
	var body string

	mux := http.NewServeMux()
	connectorsRoute(mux, typeID)
	mux.HandleFunc("/api/platform/sources/rest/probe", func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.Header.Get("Accept"), "application/json") {
			http.Error(w, "", http.StatusNotAcceptable)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		body = string(raw)
		w.Header().Set("Content-Type", "text/plain")
		_, _ = io.WriteString(w, "- id: partners\n  method: GET\n  path: /partners\n")
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, &body
}

func runRestProbe(t *testing.T, srv *httptest.Server) (string, error) {
	t.Helper()
	out, _, err := runLm(t, srv, "source", "rest", "probe", "acme", "--endpoint", "/partners")
	return out, err
}

func TestSourceRestProbe_doesNotAskForJsonFromAYamlRoute(t *testing.T) {
	srv, sent := probeServer(t, "rest-generic")

	out, err := runRestProbe(t, srv)
	if err != nil {
		t.Fatalf("probe: %v (out=%s)", err, out)
	}
	if !strings.Contains(out, "path: /partners") {
		t.Errorf("expected the drafted fragment on stdout, got %q", out)
	}
	// The connector is the request: the hub resolves the connection and the credential from it.
	if !strings.Contains(*sent, "connectorId") {
		t.Errorf("expected the connector id in the body, got %q", *sent)
	}
}

// The same refusal `lm source rest test` makes, for the same reason: every other family lacks the
// connection blocks a REST probe reads, so walking on would fail somewhere deep and never mention
// the word jdbc.
func TestSourceRestProbe_refusesANonRestConnectorByName(t *testing.T) {
	srv, _ := probeServer(t, "jdbc-generic")

	out, err := runRestProbe(t, srv)
	if err == nil {
		t.Fatalf("expected a refusal for a jdbc connector, out=%s", out)
	}
	if !strings.Contains(err.Error(), "jdbc-generic") || !strings.Contains(err.Error(), "rest-generic") {
		t.Errorf("the refusal must name both the type it got and the one it wants, got %q", err)
	}
}
