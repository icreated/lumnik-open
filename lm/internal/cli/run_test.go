package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// The run ledger exists for human diagnosis — the CONNECTOR column must speak the
// connector's NAME when the hub provides it (the first real Windows test could not
// map a Failed run back to its connector from a truncated UUID), and fall back to
// the short id against older hubs that don't send the name yet.
func TestRunRow_prefersConnectorName(t *testing.T) {
	named := runRow(types.Run{ID: "r1", ConnectorID: "cbcdc361-dead-beef", ConnectorName: "siebel-customers"})
	if named[1] != "siebel-customers" {
		t.Errorf("CONNECTOR cell should carry the name, got %q", named[1])
	}
	bare := runRow(types.Run{ID: "r2", ConnectorID: "cbcdc361-dead-beef"})
	if bare[1] != "cbcdc361" {
		t.Errorf("CONNECTOR cell should fall back to the short id, got %q", bare[1])
	}
}

// A connector fans out over its endpoints and writes ONE run row per endpoint, so a two-table
// jdbc connector logs two rows per run. Before the ENDPOINT column those rows were
// indistinguishable — same connector, same trigger, same minute — and the docs told the reader to
// tell them apart by their counters. The cell names the endpoint (the source TABLE for jdbc, the
// author's endpoint id for rest), falls back to the short id, and stays EMPTY for a run that has
// no endpoint at all rather than inventing a placeholder.
func TestRunRow_namesTheEndpoint(t *testing.T) {
	named := runRow(types.Run{ID: "r1", ConnectorName: "demo-erp",
		EndpointID: "9f2c1d40-dead-beef", EndpointName: "warehouses"})
	if named[2] != "warehouses" {
		t.Errorf("ENDPOINT cell should carry the endpoint name, got %q", named[2])
	}
	bare := runRow(types.Run{ID: "r2", ConnectorName: "demo-erp", EndpointID: "9f2c1d40-dead-beef"})
	if bare[2] != "9f2c1d40" {
		t.Errorf("ENDPOINT cell should fall back to the short id, got %q", bare[2])
	}
	none := runRow(types.Run{ID: "r3", ConnectorName: "demo-erp"})
	if none[2] != "" {
		t.Errorf("a run with no endpoint must render an empty cell, got %q", none[2])
	}
}

// The two sibling rows of one fan-out are told apart by that column and nothing else: same
// connector, same status, same counters shape. This is the defect the column exists for.
func TestRunRows_ofOneConnectorDifferByEndpoint(t *testing.T) {
	a := runRow(types.Run{ID: "41b06fa9", ConnectorName: "demo-erp", EndpointName: "warehouses"})
	b := runRow(types.Run{ID: "18c3897f", ConnectorName: "demo-erp", EndpointName: "price_list"})
	if a[1] != b[1] {
		t.Fatalf("test setup: both rows must be the same connector, got %q and %q", a[1], b[1])
	}
	if a[2] == b[2] {
		t.Errorf("two rows of one connector must not read identically: both %q", a[2])
	}
}

// Header and row must stay the same width, or every cell after an added column lands under the
// wrong title — which is exactly how the ENDPOINT column could have been shipped. The header's
// CONTENT is pinned by TestRunHeaders_includesSkippedBetweenOutAndDeadLetters; this pins that the
// row still answers it.
func TestRunRow_widthMatchesHeaders(t *testing.T) {
	row := runRow(types.Run{ID: "r1"})
	if len(row) != len(runHeaders) {
		t.Fatalf("runRow has %d cells, runHeaders has %d: %v vs %v", len(row), len(runHeaders), row, runHeaders)
	}
}

// STARTED/ENDED must render like every other date column in the CLI (output.HumanTime:
// reader-local, "2006-01-02 15:04") — before this, runRow formatted them by hand with
// time.RFC3339, so a run table printed "2026-09-03T13:43:24.11856Z" while lm connector
// list's LAST RUN column, one command over, printed "2026-09-03 15:43". Expected value
// computed the same way HumanTime's own test computes it, not hardcoded, so this does not
// depend on the machine running it being on any particular timezone.
func TestRunRow_datesRenderHuman(t *testing.T) {
	started := time.Date(2026, 9, 3, 13, 43, 24, 118560000, time.UTC)
	ended := time.Date(2026, 9, 3, 13, 43, 55, 465597000, time.UTC)
	wantStarted := started.In(time.Local).Format("2006-01-02 15:04")
	wantEnded := ended.In(time.Local).Format("2006-01-02 15:04")

	row := runRow(types.Run{ID: "r1", ConnectorID: "c1", StartedAt: started, EndedAt: &ended})
	if row[8] != wantStarted {
		t.Errorf("STARTED cell = %q, want %q (was RFC3339 before the fix)", row[8], wantStarted)
	}
	if row[9] != wantEnded {
		t.Errorf("ENDED cell = %q, want %q (was RFC3339 before the fix)", row[9], wantEnded)
	}
}

// A run still in flight has no EndedAt, and a run the hub never actually started (should not
// happen, but the zero value is reachable) has no StartedAt either — both must render empty,
// not "0001-01-01" or a nil-pointer panic.
func TestRunRow_zeroAndNilDatesRenderEmpty(t *testing.T) {
	row := runRow(types.Run{ID: "r1", ConnectorID: "c1"})
	if row[8] != "" {
		t.Errorf("STARTED cell for a zero time = %q, want empty", row[8])
	}
	if row[9] != "" {
		t.Errorf("ENDED cell for a still-running run (nil EndedAt) = %q, want empty", row[9])
	}
}

// SKIPPED joins the run table between OUT and DEAD-LETTERS — the true conservation
// invariant (recordsIn = recordsOut + recordsSkipped) is now readable at a glance
// instead of requiring a mental subtraction.
func TestRunHeaders_includesSkippedBetweenOutAndDeadLetters(t *testing.T) {
	want := []string{"ID", "CONNECTOR", "ENDPOINT", "STATUS", "IN", "OUT", "SKIPPED", "DEAD-LETTERS", "STARTED", "ENDED"}
	if len(runHeaders) != len(want) {
		t.Fatalf("runHeaders = %v, want %v", runHeaders, want)
	}
	for i, h := range want {
		if runHeaders[i] != h {
			t.Errorf("runHeaders[%d] = %q, want %q", i, runHeaders[i], h)
		}
	}
}

func TestRunRow_includesSkippedCount(t *testing.T) {
	row := runRow(types.Run{ID: "r1", ConnectorID: "c1",
		RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3, DeadLetterCount: 1})
	if row[6] != "3" {
		t.Errorf("SKIPPED cell = %q, want %q", row[6], "3")
	}
	if row[7] != "1" {
		t.Errorf("DEAD-LETTERS cell (shifted by SKIPPED) = %q, want %q", row[7], "1")
	}
}

// A Failed run's error message must reach the human — 'run scheduled' then a mute
// Failed in the list was one of the test night's silences. Stderr, like the
// quarantine alert: stdout stays machine-parseable.
func TestRunGet_failedRunSurfacesErrorOnStderr(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "07070707-0000-4000-8000-000000000007", "connectorId": "c1", "connectorName": "siebel-customers",
			"status": "Failed", "errorMessage": "connect refused: host.docker.internal:8077",
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	outBuf, errBuf := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(outBuf)
	cmd.SetErr(errBuf)
	cmd.SetArgs([]string{"run", "get", "07070707-0000-4000-8000-000000000007", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(errBuf.String(), "connect refused") {
		t.Errorf("the failure reason should be on stderr: %q", errBuf.String())
	}
	if strings.Contains(outBuf.String(), "✖") {
		t.Errorf("stdout (json) must not carry the alert: %q", outBuf.String())
	}
}

// A Cancelled run carries an errorMessage too ("cancelled by user", "skipped:
// another run is already active…") — labeling it "run failed" would lie. The
// note's label must follow the status.
func TestRunGet_cancelledRunIsNotLabeledFailed(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "06060606-0000-4000-8000-000000000006", "connectorId": "c1",
			"status": "Cancelled", "errorMessage": "cancelled by user",
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	outBuf, errBuf := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(outBuf)
	cmd.SetErr(errBuf)
	cmd.SetArgs([]string{"run", "get", "06060606-0000-4000-8000-000000000006", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(errBuf.String(), "run failed") {
		t.Errorf("a Cancelled run must not be labeled failed: %q", errBuf.String())
	}
	if !strings.Contains(errBuf.String(), "cancelled by user") {
		t.Errorf("the reason should still reach stderr: %q", errBuf.String())
	}
}

func TestRunGet_quarantineAlertOnStderrNotStdout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "09090909-0000-4000-8000-000000000009", "connectorId": "c1", "status": "Completed",
			"recordsIn": 10, "recordsOut": 7, "deadLetterCount": 3,
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	outBuf, errBuf := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(outBuf)
	cmd.SetErr(errBuf)
	cmd.SetArgs([]string{"run", "get", "09090909-0000-4000-8000-000000000009", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	// Alert on stderr (human-facing) ...
	if !strings.Contains(errBuf.String(), "quarantined") || !strings.Contains(errBuf.String(), "lm dlq list --run 09090909-0000-4000-8000-000000000009") {
		t.Errorf("alert should be on stderr: %q", errBuf.String())
	}
	// ... and stdout stays clean, parseable JSON (machine consumers / OTK).
	if strings.Contains(outBuf.String(), "quarantined") {
		t.Errorf("stdout (json) must not contain the alert: %q", outBuf.String())
	}
	var parsed []map[string]any
	if err := json.Unmarshal(outBuf.Bytes(), &parsed); err != nil {
		t.Errorf("stdout is not valid JSON: %v\n%s", err, outBuf.String())
	}
}

// Five cell hooks (parse-date, parse-number, parse-email, parse-phone, lookup) null a value
// they cannot parse and let the row through — cell_errors on the run is the only trace. `run
// get`'s detail must surface it on stderr, the same door as the quarantine alert, so an
// installer staring at a column that "arrives empty" gets a lead instead of silence.
func TestRunGet_cellErrorsAlertOnStderr(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "10101010-0000-4000-8000-000000000010", "connectorId": "c1", "status": "Completed",
			"recordsIn": 10, "recordsOut": 10, "cellErrors": 4,
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	outBuf, errBuf := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(outBuf)
	cmd.SetErr(errBuf)
	cmd.SetArgs([]string{"run", "get", "10101010-0000-4000-8000-000000000010", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(errBuf.String(), "4") || !strings.Contains(errBuf.String(), "hook") {
		t.Errorf("cell_errors alert should be on stderr: %q", errBuf.String())
	}
	// stdout is JSON and legitimately carries the "cellErrors" field name — the alert
	// itself (the glyph + "hook" wording) is what must stay off stdout.
	if strings.Contains(outBuf.String(), "⚠") || strings.Contains(outBuf.String(), "hook") {
		t.Errorf("stdout (json) must not carry the alert: %q", outBuf.String())
	}
}

func TestRunGet_noCellErrorsAlertWhenZero(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "11111111-0000-4000-8000-000000000011", "connectorId": "c1", "status": "Completed",
			"recordsIn": 10, "recordsOut": 10, "cellErrors": 0,
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"run", "get", "11111111-0000-4000-8000-000000000011"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(buf.String(), "hook") {
		t.Errorf("unexpected cell_errors alert: %s", buf.String())
	}
}

func TestRunGet_noAlertWhenZeroDeadLetters(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "08080808-0000-4000-8000-000000000008", "connectorId": "c1", "status": "Completed",
			"recordsIn": 10, "recordsOut": 10, "deadLetterCount": 0,
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"run", "get", "08080808-0000-4000-8000-000000000008"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if strings.Contains(out, "quarantined") {
		t.Errorf("unexpected quarantine alert: %s", out)
	}
}

// A successful cancel request is not a query result — it goes to stderr, same
// contract as run get's alerts, so stdout stays machine-parseable for every
// output format. The 202 body carries no useful fields for a human beyond
// what the note already says, so nothing is printed to stdout at all.
func TestRunCancel_acceptedPrintsNoteOnStderr(t *testing.T) {
	id := "aaaaaaaa-0000-4000-8000-00000000aaaa"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || !strings.HasSuffix(r.URL.Path, "/cancel") {
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]any{"status": "cancellation requested", "runId": id})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	outBuf, errBuf := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(outBuf)
	cmd.SetErr(errBuf)
	cmd.SetArgs([]string{"run", "cancel", id})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	want := "cancellation requested — the run ends after the current chunk; re-read with: lm run get " + id
	if !strings.Contains(errBuf.String(), want) {
		t.Errorf("stderr note = %q, want it to contain %q", errBuf.String(), want)
	}
	if outBuf.String() != "" {
		t.Errorf("stdout must stay empty, got %q", outBuf.String())
	}
}

// A 409 means the hub refused for a specific, diagnosable reason (already
// ended, or orphaned) — that reason must reach the human unchanged, not a
// generic "request failed".
func TestRunCancel_terminalRunSurfacesHubReason(t *testing.T) {
	id := "bbbbbbbb-0000-4000-8000-00000000bbbb"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]any{"error": "run " + id + " already ended (Completed)"})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	outBuf, errBuf := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(outBuf)
	cmd.SetErr(errBuf)
	cmd.SetArgs([]string{"run", "cancel", id})
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected an error for a run that already ended")
	}
	if !strings.Contains(err.Error(), "already ended (Completed)") {
		t.Errorf("the hub's reason should reach the human unchanged: %v", err)
	}
}

// What `run list` displays (an 8-character id) must work as the argument to
// `run cancel` — the same wiring TestRunGet_acceptsTheIdThatListDisplayed
// guards for `run get`.
func TestRunCancel_resolvesIDPrefix(t *testing.T) {
	full := "cccccccc-0000-4000-8000-0000000000cc"
	var fetched string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/runs") {
			_, _ = io.WriteString(w, `[{"id":"`+full+`","status":"Running"}]`)
			return
		}
		fetched = strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/platform/runs/"), "/cancel")
		w.WriteHeader(http.StatusAccepted)
		_, _ = io.WriteString(w, `{"status":"cancellation requested","runId":"`+full+`"}`)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"run", "cancel", full[:8]})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("the id `run list` displayed was refused: %v (out=%s)", err, buf.String())
	}
	if fetched != full {
		t.Errorf("fetched %q, want the full id %q", fetched, full)
	}
}

// The silence this fixes: `lm run list --connector X` fetched the newest 50 runs of the WHOLE
// hub and filtered them client-side, so a connector whose runs had scrolled out of that window
// printed a bare header — which a reader takes for "this connector never ran". The stub below IS
// that window: it answers the connector's run only when the request carries ?connector=, and
// otherwise hands back a busy hub's global page in which the connector does not appear.
func TestRunList_connectorFilterReachesTheHub(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/platform/connectors":
			_, _ = w.Write([]byte(`[{"id":"c-1","name":"demo-erp"}]`))
		case r.URL.Query().Get("connector") == "c-1":
			_, _ = w.Write([]byte(`[{"id":"41b06fa9-0000-4000-8000-000000000001","connectorId":"c-1",` +
				`"connectorName":"demo-erp","endpointName":"warehouses","status":"Completed"}]`))
		default:
			// The global page of a busy hub: newer runs of OTHER connectors, filling the window.
			_, _ = w.Write([]byte(`[{"id":"99999999-0000-4000-8000-000000000009","connectorId":"c-9",` +
				`"connectorName":"noisy-neighbour","status":"Completed"}]`))
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(out)
	cmd.SetArgs([]string{"run", "list", "--connector", "demo-erp"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "warehouses") {
		t.Errorf("the connector's run must be listed, got:\n%s", out.String())
	}
	if strings.Contains(out.String(), "noisy-neighbour") {
		t.Errorf("another connector's run must not appear under --connector, got:\n%s", out.String())
	}
}

// A full page is a boundary, not an end — the sibling door (`lm dlq list`) has said so for a
// while, and the run ledger, whose page is 50 by default, never did: a reader who narrows to a
// connector and gets exactly 50 rows cannot tell that history from the whole of it. Same note,
// same wording, and the same heuristic the DLQ note owns — len == limit can be an exact fit,
// which is why it says "may". That the note APPEARS on a full page is pinned once for every
// paging command by TestEveryPagingCommandAnnouncesAFullPage; what is specific to this one lives
// here: that it stays silent below the limit, and that it measures against the hub's cap.
func TestRunList_truncationHint(t *testing.T) {
	page := func(n int) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			runs := make([]map[string]any, n)
			for i := range runs {
				runs[i] = map[string]any{"id": fmt.Sprintf("r%d", i), "status": "Completed"}
			}
			_ = json.NewEncoder(w).Encode(runs)
		}
	}

	t.Run("absent when the page is not full", func(t *testing.T) {
		srv := httptest.NewServer(page(2))
		defer srv.Close()
		setupCfg(t, srv.URL)

		cmd := NewRootCmd()
		buf := new(bytes.Buffer)
		cmd.SetOut(buf)
		cmd.SetErr(buf)
		cmd.SetArgs([]string{"run", "list", "--limit", "5"})
		if err := cmd.Execute(); err != nil {
			t.Fatal(err)
		}
		if strings.Contains(buf.String(), "there may be more") {
			t.Errorf("unexpected truncation note: %q", buf.String())
		}
	})

	// The hub clamps the page to 200. Comparing against the RAW --limit would silence the note
	// exactly where the page is most certainly truncated.
	t.Run("compares against the hub's cap, not the raw --limit", func(t *testing.T) {
		srv := httptest.NewServer(page(200))
		defer srv.Close()
		setupCfg(t, srv.URL)

		cmd := NewRootCmd()
		out, errBuf := new(bytes.Buffer), new(bytes.Buffer)
		cmd.SetOut(out)
		cmd.SetErr(errBuf)
		cmd.SetArgs([]string{"run", "list", "--limit", "500"})
		if err := cmd.Execute(); err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(errBuf.String(), "there may be more") {
			t.Errorf("expected the truncation note on stderr, got: %q", errBuf.String())
		}
	})
}

// A full id needs no lookup. Resolving it anyway costs a second HTTP round trip — and server-side
// a listing that runs one "latest run" query per connector — purely to map a UUID onto itself.
// `lm dlq list` has always read its filters this way; this pins that `run list` does too.
func TestRunList_aFullConnectorIdSkipsTheNameLookup(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"run", "list", "--connector", "9f2c1d40-0000-4000-8000-000000000001"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	for _, p := range paths {
		if strings.HasPrefix(p, "/api/platform/connectors") {
			t.Errorf("a full id must not trigger a connector listing, got requests: %v", paths)
		}
	}
}
