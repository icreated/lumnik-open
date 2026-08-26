package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
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

func TestRunList_hitsRunsEndpoint(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("limit") != "10" {
			t.Errorf("limit param = %q, want 10", r.URL.Query().Get("limit"))
		}
		_, _ = w.Write([]byte(`[{"id":"run-1","connectorId":"c-1","status":"Completed"}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	runs, err := c.RunList(10)
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
	runs, err := c.RunList(200)
	if err != nil {
		t.Fatal(err)
	}
	if len(runs) != 1 || runs[0].ErrorMessage != "connection refused: siebel:8077" {
		t.Fatalf("errorMessage not parsed: %+v", runs)
	}
}
