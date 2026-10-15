package client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestSearchList_decodesSummaries(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/search/index" {
			w.WriteHeader(404)
			return
		}
		_, _ = w.Write([]byte(`[{"name":"customers","status":"ready","liveGeneration":"gen-7",
			"documentCount":100,"documentCountStale":false,"documentCountAsOf":"2026-09-27T08:00:00Z",
			"lagSeconds":0,"lastRebuild":null,"invalid":false,"invalidReason":null}]`))
	}))
	t.Cleanup(srv.Close)

	out, err := New(config.Context{Server: srv.URL, Token: "x"}).SearchList()
	if err != nil {
		t.Fatalf("SearchList: %v", err)
	}
	if len(out) != 1 || out[0].Name != "customers" || out[0].Status != "ready" ||
		out[0].DocumentCount == nil || *out[0].DocumentCount != 100 || out[0].ActiveRun != nil {
		t.Fatalf("decoded %+v", out)
	}
}

func TestSearchStatus_decodesDetailWithActiveRun(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.EscapedPath()
		_, _ = w.Write([]byte(`{"name":"customers","status":"building","liveGeneration":"gen-7",
			"documentCount":100,"documentCountStale":false,"documentCountAsOf":"2026-09-27T08:00:00Z",
			"lagSeconds":5,"activeRun":{"runId":"11111111-1111-1111-1111-111111111111","phase":"counting",
			"startedAt":"2026-09-27T08:00:00Z"},"lastRebuild":null,"lastError":null,"invalid":false,
			"invalidReason":null}`))
	}))
	t.Cleanup(srv.Close)

	got, err := New(config.Context{Server: srv.URL, Token: "x"}).SearchStatus("a/b")
	if err != nil {
		t.Fatalf("SearchStatus: %v", err)
	}
	if gotPath != "/api/platform/search/index/a%2Fb/status" {
		t.Errorf("name not escaped into one segment: %q", gotPath)
	}
	if got.ActiveRun == nil || got.ActiveRun.RunID != "11111111-1111-1111-1111-111111111111" || got.ActiveRun.Phase != "counting" {
		t.Errorf("decoded %+v", got)
	}
}

func TestSearchRebuildStart_returnsTheClaimedRunID(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.Method + " " + r.URL.Path
		w.WriteHeader(202)
		_, _ = w.Write([]byte(`{"runId":"11111111-1111-1111-1111-111111111111"}`))
	}))
	t.Cleanup(srv.Close)

	runID, err := New(config.Context{Server: srv.URL, Token: "x"}).SearchRebuildStart(context.Background(), "customers")
	if err != nil || runID == "" {
		t.Fatalf("err=%v runID=%q", err, runID)
	}
	if gotPath != "POST /api/platform/search/index/customers/rebuild" {
		t.Errorf("path = %q", gotPath)
	}
}

func TestSearchRebuildStart_acceptedWithoutRunIDIsAnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(202)
	}))
	t.Cleanup(srv.Close)
	if _, err := New(config.Context{Server: srv.URL, Token: "x"}).SearchRebuildStart(context.Background(), "customers"); err == nil {
		t.Fatal("a 202 naming no runId must not pass for a started rebuild")
	}
}

func TestSearchCancel_conflictSurfacesTheHubsReasonVerbatim(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(409)
		_, _ = w.Write([]byte(`{"error":"rebuild already past its cancellable point","runId":"11111111-1111-1111-1111-111111111111"}`))
	}))
	t.Cleanup(srv.Close)

	err := New(config.Context{Server: srv.URL, Token: "x"}).SearchCancel(context.Background(), "customers")
	if err == nil {
		t.Fatal("expected an error")
	}
	if !strings.Contains(err.Error(), "past its cancellable point") {
		t.Errorf("hub wording lost: %v", err)
	}
}

func TestSearchCancel_acceptedIsNotAnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(202)
		_, _ = w.Write([]byte(`{"runId":"11111111-1111-1111-1111-111111111111"}`))
	}))
	t.Cleanup(srv.Close)

	if err := New(config.Context{Server: srv.URL, Token: "x"}).SearchCancel(context.Background(), "customers"); err != nil {
		t.Fatalf("SearchCancel: %v", err)
	}
}
