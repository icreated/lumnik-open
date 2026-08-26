package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

func TestFilterRunsByConnector(t *testing.T) {
	runs := []types.Run{
		{ID: "r1", ConnectorID: "c-1"},
		{ID: "r2", ConnectorID: "c-2"},
		{ID: "r3", ConnectorID: "c-1"},
	}
	got := filterRunsByConnector(runs, "c-1")
	if len(got) != 2 || got[0].ID != "r1" || got[1].ID != "r3" {
		t.Fatalf("got %+v", got)
	}
	if len(filterRunsByConnector(runs, "c-9")) != 0 {
		t.Fatal("expected no matches for c-9")
	}
}

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

// SKIPPED joins the run table between OUT and DEAD-LETTERS — the true conservation
// invariant (recordsIn = recordsOut + recordsSkipped) is now readable at a glance
// instead of requiring a mental subtraction.
func TestRunHeaders_includesSkippedBetweenOutAndDeadLetters(t *testing.T) {
	want := []string{"ID", "CONNECTOR", "STATUS", "IN", "OUT", "SKIPPED", "DEAD-LETTERS", "STARTED", "ENDED"}
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
	if row[5] != "3" {
		t.Errorf("SKIPPED cell = %q, want %q", row[5], "3")
	}
	if row[6] != "1" {
		t.Errorf("DEAD-LETTERS cell (shifted by SKIPPED) = %q, want %q", row[6], "1")
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
