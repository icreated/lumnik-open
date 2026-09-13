package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

func TestRunGet_parsesRun(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/runs/run-42" {
			t.Errorf("path = %q, want /api/platform/runs/run-42", r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(types.Run{
			ID: "run-42", ConnectorID: "c-1", Status: "Completed",
			RecordsIn: 3, RecordsOut: 2,
		})
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	run, err := c.RunGet("run-42")
	if err != nil {
		t.Fatal(err)
	}
	if run.ID != "run-42" || run.Status != "Completed" || run.RecordsIn != 3 || run.RecordsOut != 2 {
		t.Fatalf("got %+v", run)
	}
}

func TestRunGet_parsesDeadLetterCount(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(types.Run{ID: "run-9", Status: "Completed", DeadLetterCount: 3})
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	run, err := c.RunGet("run-9")
	if err != nil {
		t.Fatal(err)
	}
	if run.DeadLetterCount != 3 {
		t.Fatalf("deadLetterCount = %d, want 3", run.DeadLetterCount)
	}
}

// The tests around this one encode a types.Run on the stub side and decode it back — the same
// struct tags produce and consume the JSON, so they are a tautology about field NAMES and could
// never catch the client and the hub disagreeing on one. This test writes the hub's wire shape as
// a raw literal instead: the names here are the record component names of RunAdminDto, and the
// Java side pins the same ones (RunAdminResourceTest reads .body("endpointName", ...)). Rename a
// field on either side and exactly one of the two goes red.
func TestRunGet_parsesTheHubsWireNames(t *testing.T) {
	const wire = `{
      "id": "9d1f0e33-0000-0000-0000-000000000001",
      "connectorId": "487ed9d1-58d5-4120-969c-dc448910e1f2",
      "connectorName": "demo-erp",
      "endpointId": "0f2b71c4-0000-0000-0000-000000000002",
      "endpointName": "warehouses",
      "status": "Completed",
      "startedAt": "2026-09-08T01:20:11.421Z",
      "endedAt": "2026-09-08T01:20:12.008Z",
      "recordsIn": 2, "recordsOut": 2, "recordsSkipped": 0, "cellErrors": 0,
      "triggeredBy": "MANUAL", "chunkCount": 2,
      "errorMessage": null, "deadLetterCount": 0
    }`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(wire))
	}))
	defer srv.Close()

	run, err := New(config.Context{Server: srv.URL}).RunGet("any")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct{ field, got, want string }{
		{"connectorName", run.ConnectorName, "demo-erp"},
		{"endpointName", run.EndpointName, "warehouses"},
		{"endpointId", run.EndpointID, "0f2b71c4-0000-0000-0000-000000000002"},
	} {
		if c.got != c.want {
			t.Errorf("%s did not cross the wire: got %q, want %q", c.field, c.got, c.want)
		}
	}
	if run.RecordsIn != 2 || run.ChunkCount != 2 {
		t.Errorf("counters did not cross the wire: %+v", run)
	}
}

func TestRunList_hitsRunsEndpoint(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("limit") != "10" {
			t.Errorf("limit param = %q, want 10", r.URL.Query().Get("limit"))
		}
		_, _ = w.Write([]byte(`[{"id":"run-1","connectorId":"c-1","status":"Completed"}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	runs, err := c.RunList("", "", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(runs) != 1 || runs[0].ID != "run-1" {
		t.Fatalf("got %+v", runs)
	}
}

func TestRunList_parsesErrorMessage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]types.Run{
			{ID: "run-7", Status: "FAILED", ErrorMessage: "connection refused: siebel:8077"},
		})
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	runs, err := c.RunList("", "", 200)
	if err != nil {
		t.Fatal(err)
	}
	if len(runs) != 1 || runs[0].ErrorMessage != "connection refused: siebel:8077" {
		t.Fatalf("errorMessage not parsed: %+v", runs)
	}
}

// The filters belong to the QUERY, not to the client: filtering a global page after the fact
// answers about the newest N runs of the whole hub, never about what was asked for. This pins the
// wire — the twin of DlqList's ?connector=, down to the parameter names. ?endpoint= is the finer
// half of the same address: a connector writes one run row per endpoint, so only it can answer
// "this table's runs".
func TestRunList_sendsTheFiltersAsQueryParams(t *testing.T) {
	var gotQuery url.Values
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.Query()
		_, _ = w.Write([]byte(`[{"id":"run-1","connectorId":"c-1","status":"Completed"}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	if _, err := c.RunList("c-1", "e-7", 10); err != nil {
		t.Fatal(err)
	}
	if got := gotQuery.Get("connector"); got != "c-1" {
		t.Errorf("connector param = %q, want c-1", got)
	}
	if got := gotQuery.Get("endpoint"); got != "e-7" {
		t.Errorf("endpoint param = %q, want e-7", got)
	}
	if got := gotQuery.Get("limit"); got != "10" {
		t.Errorf("limit param = %q, want 10", got)
	}
}

// An empty filter must not become ?connector= / ?endpoint= — an empty value is a value, and the
// hub would have to guess whether it means "every one" or "the one named nothing".
func TestRunList_omitsTheFilterParamsWhenUnfiltered(t *testing.T) {
	var gotQuery url.Values
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.Query()
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	if _, err := c.RunList("", "", 50); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"connector", "endpoint"} {
		if _, present := gotQuery[p]; present {
			t.Errorf("unfiltered list must send no %s param, got %v", p, gotQuery)
		}
	}
}
