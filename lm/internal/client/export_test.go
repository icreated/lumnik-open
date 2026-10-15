package client

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

// A Completed run as ExportRunView serializes it, nulls included.
const completedRunJSON = `{"id":"3f1c0000-0000-0000-0000-000000000001","export":"customers-daily",
 "scope":"sales","entity":"Customer","destinationKind":"s3","trigger":"manual","triggeredBy":7,
 "occurrence":null,"attempt":1,"retryOf":null,"status":"Completed",
 "startedAt":"2026-09-24T04:00:00Z","endedAt":"2026-09-24T04:00:03Z","rowCount":1200,
 "byteCount":48000,"sha256":"ab12","location":"s3://bucket/customers-2026-09-24.csv",
 "versionId":"v1","etag":"e1","failureCode":null,"failureReason":null}`

func TestExportApply_postsYamlVerbatim(t *testing.T) {
	manifest := "apiVersion: apps.lumnik.io/v1\nkind: Export\nmetadata:\n  name: Customers-Daily\n"
	var gotPath, gotType, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotType = r.Method+" "+r.URL.Path, r.Header.Get("Content-Type")
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		_, _ = w.Write([]byte(`{"name":"customers-daily","scope":"sales","entity":"Customer"}`))
	}))
	t.Cleanup(srv.Close)

	got, err := New(config.Context{Server: srv.URL, Token: "x"}).ExportApply([]byte(manifest))
	if err != nil {
		t.Fatalf("ExportApply: %v", err)
	}
	if gotPath != "POST /api/exports/apply" || gotType != "application/yaml" {
		t.Errorf("request = %q %q", gotPath, gotType)
	}
	if gotBody != manifest {
		t.Errorf("body rewritten in flight: %q", gotBody)
	}
	if *got != (ExportApplied{Name: "customers-daily", Scope: "sales", Entity: "Customer"}) {
		t.Errorf("decoded %+v", *got)
	}
}

func TestExportList_decodesSummaries(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/exports" {
			w.WriteHeader(404)
			return
		}
		_, _ = w.Write([]byte(`[{"name":"customers-daily","scope":"sales","entityName":"Customer",
			"destinationKind":"sftp","schedule":"0 4 * * *"},
			{"name":"adhoc","scope":"sales","entityName":"Order","destinationKind":"s3","schedule":null}]`))
	}))
	t.Cleanup(srv.Close)

	got, err := New(config.Context{Server: srv.URL, Token: "x"}).ExportList()
	if err != nil {
		t.Fatalf("ExportList: %v", err)
	}
	if len(got) != 2 || got[0].Entity != "Customer" || got[0].Schedule != "0 4 * * *" || got[1].Schedule != "" {
		t.Errorf("decoded %+v", got)
	}
}

func TestExportManifest_escapesNameAndAcceptsYaml(t *testing.T) {
	var gotURI, gotAccept string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI, gotAccept = r.URL.EscapedPath(), r.Header.Get("Accept")
		_, _ = w.Write([]byte("kind: Export\n"))
	}))
	t.Cleanup(srv.Close)

	got, err := New(config.Context{Server: srv.URL, Token: "x"}).ExportManifest("a/b")
	if err != nil {
		t.Fatalf("ExportManifest: %v", err)
	}
	if gotURI != "/api/exports/a%2Fb" {
		t.Errorf("name not escaped into one segment: %q", gotURI)
	}
	if !strings.HasPrefix(gotAccept, "application/yaml") || !strings.Contains(gotAccept, "application/json") {
		t.Errorf("Accept = %q", gotAccept)
	}
	if string(got) != "kind: Export\n" {
		t.Errorf("body = %q", got)
	}
}

func TestExportRunStart_returnsTheClaimedRunID(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.Method + " " + r.URL.Path
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"runId":"3f1c0000-0000-0000-0000-000000000001"}`))
	}))
	t.Cleanup(srv.Close)

	id, err := New(config.Context{Server: srv.URL, Token: "x"}).ExportRunStart(context.Background(), "customers-daily")
	if err != nil {
		t.Fatalf("ExportRunStart: %v", err)
	}
	if gotPath != "POST /api/exports/customers-daily/runs" || id != "3f1c0000-0000-0000-0000-000000000001" {
		t.Errorf("path %q id %q", gotPath, id)
	}
}

func TestExportRunStart_acceptedWithoutRunIDIsAnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusAccepted)
	}))
	t.Cleanup(srv.Close)
	if _, err := New(config.Context{Server: srv.URL, Token: "x"}).ExportRunStart(context.Background(), "e"); err == nil {
		t.Fatal("a 202 naming no run must not pass for a started run")
	}
}

func TestExportRunRetry_postsToTheRunsRetryDoor(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.Method + " " + r.URL.Path
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"runId":"new"}`))
	}))
	t.Cleanup(srv.Close)
	id, err := New(config.Context{Server: srv.URL, Token: "x"}).ExportRunRetry(context.Background(), "e", "old")
	if err != nil || id != "new" || gotPath != "POST /api/exports/e/runs/old/retry" {
		t.Errorf("id %q path %q err %v", id, gotPath, err)
	}
}

func TestExportRuns_andGet_decodeTheLedgerRow(t *testing.T) {
	var gotURIs []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURIs = append(gotURIs, r.URL.RequestURI())
		if strings.HasSuffix(r.URL.Path, "/runs") {
			_, _ = w.Write([]byte("[" + completedRunJSON + "]"))
			return
		}
		_, _ = w.Write([]byte(completedRunJSON))
	}))
	t.Cleanup(srv.Close)
	c := New(config.Context{Server: srv.URL, Token: "x"})

	runs, err := c.ExportRuns(context.Background(), "customers-daily", 20)
	if err != nil || len(runs) != 1 {
		t.Fatalf("ExportRuns: %v %v", runs, err)
	}
	run, err := c.ExportRunGet(context.Background(), "customers-daily", runs[0].ID)
	if err != nil {
		t.Fatalf("ExportRunGet: %v", err)
	}
	want := []string{"/api/exports/customers-daily/runs?limit=20",
		"/api/exports/customers-daily/runs/3f1c0000-0000-0000-0000-000000000001"}
	if strings.Join(gotURIs, " ") != strings.Join(want, " ") {
		t.Errorf("URIs = %v", gotURIs)
	}
	if run.Status != ExportRunCompleted || run.RowCount == nil || *run.RowCount != 1200 ||
		run.Location != "s3://bucket/customers-2026-09-24.csv" || run.Occurrence != nil || run.RetryOf != "" ||
		run.StartedAt == nil || !run.StartedAt.Equal(time.Date(2026, 9, 24, 4, 0, 0, 0, time.UTC)) {
		t.Errorf("decoded %+v", run)
	}
}

func TestExportRunGet_cancelledContextAbortsTheRequest(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	t.Cleanup(func() { close(release); srv.Close() })

	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(50 * time.Millisecond); cancel() }()
	start := time.Now()
	_, err := New(config.Context{Server: srv.URL, Token: "x"}).ExportRunGet(ctx, "e", "id")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if time.Since(start) > 5*time.Second {
		t.Error("the request ran on past its cancellation")
	}
}

func TestInFlightExportRun(t *testing.T) {
	cases := []struct {
		name    string
		err     error
		wantID  string
		wantHit bool
	}{
		{"in flight with id", &APIError{Status: 409,
			Body: `{"error":"export 'e' already has a run in flight","runId":"r1"}`}, "r1", true},
		{"in flight, lock only", &APIError{Status: 409,
			Body: `{"error":"export 'e' already has a run in flight"}`}, "", true},
		{"already delivered", &APIError{Status: 409, Body: `{"error":"already delivered"}`}, "", false},
		{"not failed", &APIError{Status: 409,
			Body: `{"error":"run r1 is Running; only a Failed run can be retried"}`}, "", false},
		{"scope", &APIError{Status: 403, Body: `{"error":"scope not granted"}`}, "", false},
		{"other", errors.New("boom"), "", false},
	}
	for _, tc := range cases {
		id, hit := InFlightExportRun(tc.err)
		if id != tc.wantID || hit != tc.wantHit {
			t.Errorf("%s: got (%q, %v)", tc.name, id, hit)
		}
	}
}
