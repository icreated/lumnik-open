package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	exportRunID  = "3f1c0000-0000-0000-0000-000000000001"
	exportRunID2 = "9a000000-0000-0000-0000-000000000002"
)

func exportRunJSON(id, status string, extra string) string {
	body := fmt.Sprintf(`{"id":%q,"export":"customers-daily","scope":"sales","entity":"Customer",
		"destinationKind":"s3","trigger":"manual","triggeredBy":7,"occurrence":null,"attempt":1,
		"retryOf":null,"status":%q,"startedAt":"2026-09-24T04:00:00Z","endedAt":null,
		"rowCount":null,"byteCount":null,"sha256":null,"location":null,"versionId":null,"etag":null,
		"failureCode":null,"failureReason":null`, id, status)
	if extra != "" {
		body += "," + extra
	}
	return body + "}"
}

const completedExtra = `"endedAt":"2026-09-24T04:00:03Z","rowCount":1200,"byteCount":48000,
	"sha256":"ab12cd","location":"s3://bucket/customers-2026-09-24.csv","versionId":"v1","etag":"e1"`

const failedExtra = `"endedAt":"2026-09-24T04:00:03Z","failureCode":"publication",
	"failureReason":"the destination refused the upload"`

// exportHub is a fake hub for the export doors. getRun answers GET .../runs/{id}, one call at a
// time, so a test can script a run moving from Running to its outcome.
type exportHub struct {
	mu       sync.Mutex
	requests []string
	start    func(w http.ResponseWriter)
	getRun   func(call int) string
	gets     int
	list     string
}

func (h *exportHub) serve(t *testing.T) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		defer h.mu.Unlock()
		h.requests = append(h.requests, r.Method+" "+r.URL.RequestURI())
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/runs"),
			r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/retry"):
			h.start(w)
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/runs"):
			_, _ = fmt.Fprint(w, h.list)
		case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/runs/"):
			h.gets++
			_, _ = fmt.Fprint(w, h.getRun(h.gets))
		default:
			w.WriteHeader(http.StatusNotFound)
			_, _ = fmt.Fprint(w, `{"error":"no such door"}`)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func accepted(id string) func(w http.ResponseWriter) {
	return func(w http.ResponseWriter) {
		w.WriteHeader(http.StatusAccepted)
		_, _ = fmt.Fprintf(w, `{"runId":%q}`, id)
	}
}

// runLmCtx is runLm under a caller's context, for the cancellation paths.
func runLmCtx(t *testing.T, ctx context.Context, srv *httptest.Server, args ...string) (string, string, error) {
	t.Helper()
	setupCfg(t, srv.URL)
	root := NewRootCmd()
	root.SetArgs(args)
	out, errOut := &bytes.Buffer{}, &bytes.Buffer{}
	root.SetOut(out)
	root.SetErr(errOut)
	err := root.ExecuteContext(ctx)
	return out.String(), errOut.String(), err
}

func TestExportApply_postsTheManifestAndReportsTheCanonicalNames(t *testing.T) {
	manifest := "apiVersion: apps.lumnik.io/v1\nkind: Export\nmetadata:\n  name: Customers-Daily\n"
	var gotPath, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.Method + " " + r.URL.Path
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		_, _ = fmt.Fprint(w, `{"name":"customers-daily","scope":"sales","entity":"Customer"}`)
	}))
	t.Cleanup(srv.Close)
	f := filepath.Join(t.TempDir(), "e.yaml")
	if err := os.WriteFile(f, []byte(manifest), 0o600); err != nil {
		t.Fatal(err)
	}

	out, _, err := runLm(t, srv, "export", "apply", "-f", f)
	if err != nil {
		t.Fatalf("apply: %v", err)
	}
	if gotPath != "POST /api/exports/apply" || gotBody != manifest {
		t.Errorf("sent %q %q", gotPath, gotBody)
	}
	if out != "Applied export \"customers-daily\" → entity Customer, scope sales\n" {
		t.Errorf("out = %q", out)
	}

	out, _, err = runLm(t, srv, "export", "apply", "-f", f, "-o", "json")
	if err != nil {
		t.Fatalf("apply -o json: %v", err)
	}
	var got map[string]string
	if err := json.Unmarshal([]byte(out), &got); err != nil || got["name"] != "customers-daily" || got["entity"] != "Customer" {
		t.Errorf("json out = %q (%v)", out, err)
	}
}

func TestExportApply_refusalNeverEchoesTheSecret(t *testing.T) {
	const secret = "hunter2-do-not-print"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = fmt.Fprint(w, `{"error":"'password' is not accepted — store the credential via `+
			"`lm secret set`"+` and reference it as `+"`password_env`"+`"}`)
	}))
	t.Cleanup(srv.Close)
	f := filepath.Join(t.TempDir(), "e.yaml")
	manifest := "kind: Export\nspec:\n  destination:\n    kind: sftp\n    password: " + secret + "\n"
	if err := os.WriteFile(f, []byte(manifest), 0o600); err != nil {
		t.Fatal(err)
	}

	out, errOut, err := runLm(t, srv, "export", "apply", "-f", f)
	if err == nil {
		t.Fatal("a refused apply must fail")
	}
	if !strings.Contains(err.Error(), "API error 400") || !strings.Contains(err.Error(), "password_env") {
		t.Errorf("the hub's refusal did not reach the user: %v", err)
	}
	if strings.Contains(err.Error()+out+errOut, secret) {
		t.Error("the credential value leaked into the CLI output")
	}
}

func TestExportScopeRefusalIsPassedThroughVerbatim(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = fmt.Fprint(w, `{"error":"scope not granted"}`)
	}))
	t.Cleanup(srv.Close)

	for _, args := range [][]string{
		{"export", "get", "customers-daily"},
		{"export", "run", "customers-daily"},
		{"export", "runs", "customers-daily"},
		{"export", "status", "customers-daily"},
	} {
		out, _, err := runLm(t, srv, args...)
		if err == nil || !strings.Contains(err.Error(), "scope not granted") {
			t.Errorf("%v: err = %v", args, err)
		}
		if out != "" {
			t.Errorf("%v printed %q on a refusal", args, out)
		}
	}
}

func TestExportList_rendersTableAndJson(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = fmt.Fprint(w, `[{"name":"customers-daily","scope":"sales","entityName":"Customer",
			"destinationKind":"sftp","schedule":"0 4 * * *"}]`)
	}))
	t.Cleanup(srv.Close)

	out, _, err := runLm(t, srv, "export", "list")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	for _, want := range []string{"NAME", "DESTINATION", "customers-daily", "Customer", "sftp", "0 4 * * *"} {
		if !strings.Contains(out, want) {
			t.Errorf("table lacks %q:\n%s", want, out)
		}
	}
	out, _, err = runLm(t, srv, "export", "list", "-o", "json")
	if err != nil {
		t.Fatalf("list -o json: %v", err)
	}
	var rows []map[string]any
	if err := json.Unmarshal([]byte(out), &rows); err != nil || len(rows) != 1 || rows[0]["entityName"] != "Customer" {
		t.Errorf("json = %q (%v)", out, err)
	}
}

func TestExportGet_printsTheManifestWithURLCredentialsMasked(t *testing.T) {
	stored := "kind: Export\nspec:\n  destination:\n    kind: s3\n    endpoint: https://AKIA:s3cr3t@minio.internal:9000\n    bucket: out\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/exports/customers-daily" {
			w.WriteHeader(404)
			return
		}
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = fmt.Fprint(w, stored)
	}))
	t.Cleanup(srv.Close)

	out, _, err := runLm(t, srv, "export", "get", "customers-daily")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if strings.Contains(out, "s3cr3t") || strings.Contains(out, "AKIA") {
		t.Errorf("credential printed:\n%s", out)
	}
	if !strings.Contains(out, "endpoint: https://***@minio.internal:9000") || !strings.Contains(out, "bucket: out") {
		t.Errorf("manifest mangled:\n%s", out)
	}
}

func TestRedactURLCredentials(t *testing.T) {
	cases := map[string]string{
		"endpoint: https://u:p@h/x":        "endpoint: https://***@h/x",
		"endpoint: https://token@h":        "endpoint: https://***@h",
		"endpoint: https://h:9000/p":       "endpoint: https://h:9000/p",
		"path: /out/${date}.csv":           "path: /out/${date}.csv",
		"notify: ops@example.com":          "notify: ops@example.com",
		"a: 'sftp://me:pw@host' b: s3://b": "a: 'sftp://***@host' b: s3://b",
	}
	for in, want := range cases {
		if got := redactURLCredentials(in); got != want {
			t.Errorf("%q → %q, want %q", in, got, want)
		}
	}
}

func TestExportRun_withoutWaitReportsAClaimNotADelivery(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID)}
	srv := h.serve(t)

	out, errOut, err := runLm(t, srv, "export", "run", "customers-daily")
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if out != "export run "+exportRunID+" accepted\n" {
		t.Errorf("out = %q", out)
	}
	if !strings.Contains(errOut, "delivery is not confirmed") || !strings.Contains(errOut, "lm export status customers-daily "+exportRunID) {
		t.Errorf("stderr = %q", errOut)
	}
	if len(h.requests) != 1 || h.requests[0] != "POST /api/exports/customers-daily/runs" {
		t.Errorf("requests = %v", h.requests)
	}

	out, _, err = runLm(t, srv, "export", "run", "customers-daily", "-o", "json")
	if err != nil {
		t.Fatalf("run -o json: %v", err)
	}
	var got map[string]string
	if err := json.Unmarshal([]byte(out), &got); err != nil || got["runId"] != exportRunID || got["status"] != "accepted" {
		t.Errorf("json = %q (%v)", out, err)
	}
}

func TestExportRun_waitFollowsTheLedgerToCompleted(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID), getRun: func(call int) string {
		if call < 3 {
			return exportRunJSON(exportRunID, "Running", "")
		}
		return exportRunJSON(exportRunID, "Completed", completedExtra)
	}}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait", "--interval", "1ms")
	if err != nil {
		t.Fatalf("run --wait: %v", err)
	}
	if h.gets != 3 {
		t.Errorf("polled %d times, want 3", h.gets)
	}
	for _, want := range []string{"Completed", "1200", "48000", "ab12cd", "s3://bucket/customers-2026-09-24.csv"} {
		if !strings.Contains(out, want) {
			t.Errorf("receipt lacks %q:\n%s", want, out)
		}
	}
}

func TestExportRun_waitPollsThroughAQueuedRun(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID), getRun: func(call int) string {
		if call < 3 {
			return exportRunJSON(exportRunID, "Queued", "")
		}
		return exportRunJSON(exportRunID, "Completed", completedExtra)
	}}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait", "--interval", "1ms")
	if err != nil {
		t.Fatalf("run --wait: %v", err)
	}
	if h.gets != 3 {
		t.Errorf("polled %d times, want 3: a Queued run is not finished", h.gets)
	}
	if !strings.Contains(out, "Completed") {
		t.Errorf("receipt lacks Completed:\n%s", out)
	}
}

func TestExportRun_waitTimeoutOnAQueuedRunDoesNotCallItRunning(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID), getRun: func(int) string {
		return exportRunJSON(exportRunID, "Queued", "")
	}}
	srv := h.serve(t)

	_, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait",
		"--interval", "1ms", "--timeout", "20ms")
	if err == nil || !strings.Contains(err.Error(), "still in flight on the hub") || strings.Contains(err.Error(), "Running") {
		t.Errorf("err = %v", err)
	}
}

func TestExportRun_waitOnAFailedRunExitsNonZeroWithTheHubsReason(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID), getRun: func(int) string {
		return exportRunJSON(exportRunID, "Failed", failedExtra)
	}}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait", "--interval", "1ms", "-o", "json")
	if err == nil {
		t.Fatal("a Failed run must not exit 0 under --wait")
	}
	if !strings.Contains(err.Error(), "failed (publication): the destination refused the upload") {
		t.Errorf("err = %v", err)
	}
	var run map[string]any
	if json.Unmarshal([]byte(out), &run) != nil || run["status"] != "Failed" {
		t.Errorf("the failed run was not printed for the script: %q", out)
	}
}

func TestExportRun_waitTimeoutNeverReportsSuccess(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID), getRun: func(int) string {
		return exportRunJSON(exportRunID, "Running", "")
	}}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait",
		"--interval", "1ms", "--timeout", "20ms")
	if err == nil {
		t.Fatal("a wait that timed out must not exit 0")
	}
	if !strings.Contains(err.Error(), "still in flight on the hub") || !strings.Contains(err.Error(), exportRunID) {
		t.Errorf("err = %v", err)
	}
	if out != "" {
		t.Errorf("stdout on timeout = %q", out)
	}
}

func TestExportRun_waitStopsOnCancellation(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID), getRun: func(int) string {
		return exportRunJSON(exportRunID, "Running", "")
	}}
	srv := h.serve(t)

	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(30*time.Millisecond, cancel)
	done := make(chan struct{})
	var out string
	var err error
	go func() {
		defer close(done)
		out, _, err = runLmCtx(t, ctx, srv, "export", "run", "customers-daily", "--wait", "--interval", "5ms")
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("--wait ignored the cancellation")
	}
	if err == nil || !strings.Contains(err.Error(), "stopped waiting") || !strings.Contains(err.Error(), "continues on the hub") {
		t.Errorf("err = %v", err)
	}
	if out != "" {
		t.Errorf("stdout after cancellation = %q", out)
	}
}

func TestExportRun_inFlightConflictNamesTheRunToFollow(t *testing.T) {
	h := &exportHub{start: func(w http.ResponseWriter) {
		w.WriteHeader(http.StatusConflict)
		_, _ = fmt.Fprintf(w, `{"error":"export 'customers-daily' already has a run in flight","runId":%q}`, exportRunID)
	}}
	srv := h.serve(t)

	_, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait")
	if err == nil {
		t.Fatal("a conflict must fail")
	}
	for _, want := range []string{"API error 409", "already has a run in flight", "lm export status customers-daily " + exportRunID} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("err lacks %q: %v", want, err)
		}
	}
	if h.gets != 0 {
		t.Error("--wait must not silently adopt another caller's run")
	}
}

func TestExportRetry_resolvesAShortIDAgainstTheExportsRuns(t *testing.T) {
	h := &exportHub{
		start: accepted(exportRunID2),
		list:  "[" + exportRunJSON(exportRunID, "Failed", failedExtra) + "]",
	}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "export", "retry", "customers-daily", exportRunID[:8])
	if err != nil {
		t.Fatalf("retry: %v", err)
	}
	if !strings.Contains(out, exportRunID2) {
		t.Errorf("out = %q", out)
	}
	want := "POST /api/exports/customers-daily/runs/" + exportRunID + "/retry"
	if h.requests[len(h.requests)-1] != want {
		t.Errorf("requests = %v", h.requests)
	}
}

func TestExportRuns_listsTheLedger(t *testing.T) {
	h := &exportHub{list: "[" + exportRunJSON(exportRunID, "Completed", completedExtra) + "," +
		exportRunJSON(exportRunID2, "Failed", failedExtra) + "]"}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "export", "runs", "customers-daily", "--limit", "5")
	if err != nil {
		t.Fatalf("runs: %v", err)
	}
	if h.requests[0] != "GET /api/exports/customers-daily/runs?limit=5" {
		t.Errorf("requests = %v", h.requests)
	}
	for _, want := range []string{"STATUS", exportRunID[:8], "Completed", "1200", "Failed", "publication"} {
		if !strings.Contains(out, want) {
			t.Errorf("table lacks %q:\n%s", want, out)
		}
	}
}

func TestExportStatus_latestRunOrOneByID(t *testing.T) {
	h := &exportHub{
		list: "[" + exportRunJSON(exportRunID2, "Failed", failedExtra) + "]",
		getRun: func(int) string {
			return exportRunJSON(exportRunID, "Completed", completedExtra)
		},
	}
	srv := h.serve(t)

	out, errOut, err := runLm(t, srv, "export", "status", "customers-daily")
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	if h.requests[0] != "GET /api/exports/customers-daily/runs?limit=1" || !strings.Contains(out, exportRunID2) {
		t.Errorf("latest run not shown: %v\n%s", h.requests, out)
	}
	if !strings.Contains(errOut, "failed (publication)") || !strings.Contains(errOut, "lm export retry customers-daily") {
		t.Errorf("stderr = %q", errOut)
	}

	out, _, err = runLm(t, srv, "export", "status", "customers-daily", exportRunID, "-o", "yaml")
	if err != nil {
		t.Fatalf("status ID: %v", err)
	}
	if !strings.Contains(out, "status: Completed") || !strings.Contains(out, "sha256: ab12cd") ||
		!strings.Contains(out, "destinationKind: s3") {
		t.Errorf("yaml = %s", out)
	}
}

func TestExportStatus_noRunsYetIsAnError(t *testing.T) {
	h := &exportHub{list: "[]"}
	srv := h.serve(t)
	_, _, err := runLm(t, srv, "export", "status", "customers-daily")
	if err == nil || !strings.Contains(err.Error(), "has no runs yet") {
		t.Errorf("err = %v", err)
	}
}

func TestExportRun_waitTimeoutShorterThanTheIntervalWaitsOutTheTimeout(t *testing.T) {
	h := &exportHub{start: accepted(exportRunID), getRun: func(int) string {
		return exportRunJSON(exportRunID, "Running", "")
	}}
	srv := h.serve(t)

	start := time.Now()
	_, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait",
		"--interval", "2s", "--timeout", "100ms")
	elapsed := time.Since(start)
	if err == nil || !strings.Contains(err.Error(), "gave up waiting after 100ms") {
		t.Fatalf("err = %v", err)
	}
	if elapsed < 100*time.Millisecond {
		t.Errorf("gave up after %s, before the 100ms timeout", elapsed)
	}
	if elapsed > 1500*time.Millisecond {
		t.Errorf("waited %s: the timeout did not cut the 2s interval short", elapsed)
	}
}

func TestExportRun_waitTimeoutBoundsABlockedPoll(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			w.WriteHeader(http.StatusAccepted)
			_, _ = fmt.Fprintf(w, `{"runId":%q}`, exportRunID)
			return
		}
		select { // the ledger read never answers
		case <-release:
		case <-r.Context().Done():
		}
	}))
	t.Cleanup(func() { close(release); srv.Close() })

	start := time.Now()
	_, _, err := runLm(t, srv, "export", "run", "customers-daily", "--wait", "--timeout", "100ms")
	if err == nil || !strings.Contains(err.Error(), "gave up waiting after 100ms") {
		t.Fatalf("err = %v", err)
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Errorf("a blocked poll ran %s past a 100ms timeout", elapsed)
	}
}
