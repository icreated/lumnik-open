package cli

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	searchRunID  = "11111111-1111-1111-1111-111111111111"
	searchRunID2 = "22222222-2222-2222-2222-222222222222"
)

func activeRunJSON(runID, phase string) string {
	return fmt.Sprintf(`{"runId":%q,"phase":%q,"startedAt":"2026-09-27T08:00:00Z"}`, runID, phase)
}

func lastRebuildJSON(runID, outcome string) string {
	return fmt.Sprintf(`{"runId":%q,"outcome":%q,"finishedAt":"2026-09-27T08:05:00Z"}`, runID, outcome)
}

// searchIndexJSON renders one SearchOperationsResource body. activeRun/lastRebuild are either
// "null" or one of the two JSON builders above.
func searchIndexJSON(name, status string, lagSeconds int, activeRun, lastRebuild string) string {
	return fmt.Sprintf(`{"name":%q,"status":%q,"liveGeneration":"gen-7","documentCount":100,
		"documentCountStale":false,"documentCountAsOf":"2026-09-27T08:00:00Z","lagSeconds":%d,
		"activeRun":%s,"lastRebuild":%s,"lastError":null,"invalid":false,"invalidReason":null}`,
		name, status, lagSeconds, activeRun, lastRebuild)
}

// searchHub is a fake hub for the search operations doors. status answers GET .../{name}/status,
// one call at a time, so a test can script an index moving through a rebuild.
type searchHub struct {
	mu       sync.Mutex
	requests []string
	list     string
	status   func(call int) string
	rebuild  func(w http.ResponseWriter)
	cancel   func(w http.ResponseWriter)
	calls    int
}

func (h *searchHub) serve(t *testing.T) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		defer h.mu.Unlock()
		h.requests = append(h.requests, r.Method+" "+r.URL.RequestURI())
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/status"):
			h.calls++
			_, _ = fmt.Fprint(w, h.status(h.calls))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/index"):
			_, _ = fmt.Fprint(w, h.list)
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/rebuild"):
			h.rebuild(w)
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/cancel"):
			h.cancel(w)
		default:
			w.WriteHeader(http.StatusNotFound)
			_, _ = fmt.Fprint(w, `{"error":"no such door"}`)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestSearchStatus_noArgRendersTheListTable(t *testing.T) {
	h := &searchHub{list: "[" + searchIndexJSON("customers", "ready", 0, "null", "null") + "]"}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "status")
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	for _, want := range []string{"NAME", "STATUS", "LAG", "DOCUMENTS", "customers", "ready", "100"} {
		if !strings.Contains(out, want) {
			t.Errorf("table lacks %q:\n%s", want, out)
		}
	}
}

func TestSearchStatus_withNameRendersDetail(t *testing.T) {
	h := &searchHub{status: func(int) string {
		return searchIndexJSON("customers", "building", 5, activeRunJSON(searchRunID, "counting"), "null")
	}}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "status", "customers")
	if err != nil {
		t.Fatalf("status customers: %v", err)
	}
	for _, want := range []string{"Name", "customers", "Status", "building", "Active run", searchRunID, "counting", "Lag", "5s"} {
		if !strings.Contains(out, want) {
			t.Errorf("detail lacks %q:\n%s", want, out)
		}
	}
	if h.requests[0] != "GET /api/platform/search/index/customers/status" {
		t.Errorf("requests = %v", h.requests)
	}
}

func TestSearchRebuild_withoutWaitPrintsTheRunID(t *testing.T) {
	h := &searchHub{rebuild: accepted(searchRunID)}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "rebuild", "customers")
	if err != nil {
		t.Fatalf("rebuild: %v", err)
	}
	if out != "rebuild "+searchRunID+" started\n" {
		t.Errorf("out = %q", out)
	}
	if len(h.requests) != 1 || h.requests[0] != "POST /api/platform/search/index/customers/rebuild" {
		t.Errorf("requests = %v", h.requests)
	}
}

func TestSearchRebuild_waitReachesReadyViaLastRebuildRunID(t *testing.T) {
	h := &searchHub{
		rebuild: accepted(searchRunID),
		status: func(call int) string {
			if call < 3 {
				return searchIndexJSON("customers", "building", 0, activeRunJSON(searchRunID, "counting"), "null")
			}
			return searchIndexJSON("customers", "ready", 0, "null", lastRebuildJSON(searchRunID, "ready"))
		},
	}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "rebuild", "customers", "--wait", "--interval", "1ms")
	if err != nil {
		t.Fatalf("rebuild --wait: %v", err)
	}
	if h.calls != 3 {
		t.Errorf("polled %d times, want 3", h.calls)
	}
	for _, want := range []string{"ready", searchRunID} {
		if !strings.Contains(out, want) {
			t.Errorf("receipt lacks %q:\n%s", want, out)
		}
	}
}

func TestSearchRebuild_waitOnAFailedOutcomeExitsNonZero(t *testing.T) {
	h := &searchHub{
		rebuild: accepted(searchRunID),
		status: func(int) string {
			return searchIndexJSON("customers", "ready", 0, "null", lastRebuildJSON(searchRunID, "failed"))
		},
	}
	srv := h.serve(t)

	_, _, err := runLm(t, srv, "search", "rebuild", "customers", "--wait", "--interval", "1ms")
	if err == nil || !strings.Contains(err.Error(), "ended failed") {
		t.Fatalf("err = %v", err)
	}
}

// TestSearchRebuild_waitLosesTrackWhenADifferentRunAppears is the "lost track of run" scenario:
// the CLI never observes ITS OWN runId in either field (never a "keep waiting" state showing our
// run), and then a DIFFERENT run's id shows up in activeRun — distinct from the narrow window
// right after 202 where neither field shows ANY run yet.
func TestSearchRebuild_waitLosesTrackWhenADifferentRunAppears(t *testing.T) {
	h := &searchHub{
		rebuild: accepted(searchRunID),
		status: func(call int) string {
			if call < 2 {
				return searchIndexJSON("customers", "building", 0, "null", "null") // keep waiting: neither field shows any run yet
			}
			return searchIndexJSON("customers", "building", 0, activeRunJSON(searchRunID2, "counting"), "null")
		},
	}
	srv := h.serve(t)

	_, _, err := runLm(t, srv, "search", "rebuild", "customers", "--wait", "--interval", "1ms")
	if err == nil {
		t.Fatal("expected a lost-track error")
	}
	for _, want := range []string{"lost track of run", searchRunID, "different run is now visible"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("err lacks %q: %v", want, err)
		}
	}
	if strings.Contains(err.Error(), searchRunID2) {
		t.Errorf("the OTHER run's id must not be quoted back as if it were ours: %v", err)
	}
}

func TestSearchRebuild_waitTimeoutNeverReportsSuccess(t *testing.T) {
	h := &searchHub{
		rebuild: accepted(searchRunID),
		status: func(int) string {
			return searchIndexJSON("customers", "building", 0, activeRunJSON(searchRunID, "counting"), "null")
		},
	}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "rebuild", "customers", "--wait",
		"--interval", "1ms", "--timeout", "20ms")
	if err == nil {
		t.Fatal("a wait that timed out must not exit 0")
	}
	if !strings.Contains(err.Error(), "gave up waiting after 20ms") || !strings.Contains(err.Error(), searchRunID) {
		t.Errorf("err = %v", err)
	}
	if out != "" {
		t.Errorf("stdout on timeout = %q", out)
	}
}

// TestSearchRebuild_waitStopsOnCancellation distinguishes the interrupt path from the --timeout
// path: mirrors export.go's waitForExportRun / TestExportRun_waitStopsOnCancellation.
func TestSearchRebuild_waitStopsOnCancellation(t *testing.T) {
	h := &searchHub{
		rebuild: accepted(searchRunID),
		status: func(int) string {
			return searchIndexJSON("customers", "building", 0, activeRunJSON(searchRunID, "counting"), "null")
		},
	}
	srv := h.serve(t)

	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(30*time.Millisecond, cancel)
	done := make(chan struct{})
	var out string
	var err error
	go func() {
		defer close(done)
		out, _, err = runLmCtx(t, ctx, srv, "search", "rebuild", "customers", "--wait", "--interval", "5ms")
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("--wait ignored the cancellation")
	}
	if err == nil || !strings.Contains(err.Error(), "stopped waiting") || !strings.Contains(err.Error(), "continues on the hub") {
		t.Errorf("err = %v", err)
	}
	if strings.Contains(err.Error(), "gave up waiting after") {
		t.Errorf("an interrupt must not be reported as a --timeout: %v", err)
	}
	if out != "" {
		t.Errorf("stdout after cancellation = %q", out)
	}
}

func TestSearchCancel_conflictReasonsProduceDistinctMessages(t *testing.T) {
	cases := []struct {
		name string
		body string
		want string
	}{
		{"no active run", `{"error":"no rebuild in progress"}`, "no rebuild in progress"},
		{"past cancellable point",
			fmt.Sprintf(`{"error":"rebuild already past its cancellable point","runId":%q}`, searchRunID),
			"past its cancellable point"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h := &searchHub{cancel: func(w http.ResponseWriter) {
				w.WriteHeader(409)
				_, _ = fmt.Fprint(w, tc.body)
			}}
			srv := h.serve(t)
			_, _, err := runLm(t, srv, "search", "cancel", "customers")
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Errorf("err = %v", err)
			}
		})
	}
}

func TestSearchCancel_acceptedPrintsRequestedWording(t *testing.T) {
	h := &searchHub{cancel: accepted(searchRunID)}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "cancel", "customers")
	if err != nil {
		t.Fatalf("cancel: %v", err)
	}
	if out != "cancellation requested\n" {
		t.Errorf("out = %q", out)
	}
	if strings.Contains(out, "search not installed") || strings.Contains(out, "degraded") {
		t.Errorf("cancel must never consult the search provider: %q", out)
	}
}
