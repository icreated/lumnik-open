package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDlqList(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode([]map[string]any{
			{"id": "d1", "connectorId": "11111111-2222-3333-4444-555555555555", "connectorName": "orders-csv", "runId": "r1", "skipReason": "BAD_RECORD", "errorMessage": "boom", "attempts": 1},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "list"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "d1") || !strings.Contains(out, "orders-csv") {
		t.Errorf("the table names the connector, like run list: %s", out)
	}
	if strings.Contains(out, "11111111") {
		t.Errorf("a named connector must not also print its id in the table: %s", out)
	}
}

func TestDlqList_runFilter_resolvesAbbreviatedId(t *testing.T) {
	// The hub's `run` filter is a UUID; an 8-char abbreviation copied from `run list` must be
	// resolved to the full id before it is sent, or the hub answers "invalid UUID".
	full := "aaaaaaaa-1111-2222-3333-444444444444"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/api/platform/runs"):
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": full}})
		case r.URL.Path == "/api/platform/dlq":
			if got := r.URL.Query().Get("run"); got != full {
				t.Errorf("dlq run param = %q, want the resolved full id %q", got, full)
			}
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": "d9", "runId": full, "skipReason": "ValidationFailed"}})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "list", "--run", "aaaaaaaa"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "d9") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

func TestDlqList_connectorFilter_resolvesName(t *testing.T) {
	// `--connector NAME` must resolve to the connector's UUID before hitting the UUID-typed filter.
	connID := "bbbbbbbb-1111-2222-3333-444444444444"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/api/platform/connectors"):
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": connID, "name": "orders-csv"}})
		case r.URL.Path == "/api/platform/dlq":
			if got := r.URL.Query().Get("connector"); got != connID {
				t.Errorf("dlq connector param = %q, want the resolved id %q", got, connID)
			}
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": "d1", "connectorId": connID}})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "list", "--connector", "orders-csv"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "d1") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

func TestDlqList_sendsLimit(t *testing.T) {
	// --limit below the server cap passes through unchanged.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("limit") != "50" {
			t.Errorf("limit param = %q, want 50", r.URL.Query().Get("limit"))
		}
		_ = json.NewEncoder(w).Encode([]map[string]any{{"id": "d1"}})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "list", "--limit", "50"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
}

// TestDlqList_hintFiresAtServerCap: when the user passes --limit > server cap (200),
// the client must clamp the request to 200 and fire the hint when 200 items come back.
func TestDlqList_hintFiresAtServerCap(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// (a) client must send the clamped limit, not the user-supplied 500
		if r.URL.Query().Get("limit") != "200" {
			t.Errorf("limit param = %q, want 200 (server cap)", r.URL.Query().Get("limit"))
		}
		// (b) server returns exactly 200 items (its maximum)
		items := make([]map[string]any, 200)
		for i := range items {
			items[i] = map[string]any{"id": fmt.Sprintf("d%d", i)}
		}
		_ = json.NewEncoder(w).Encode(items)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	out := new(bytes.Buffer)
	errBuf := new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(errBuf)
	cmd.SetArgs([]string{"dlq", "list", "--limit", "500"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(errBuf.String(), "there may be more") {
		t.Errorf("expected truncation hint in stderr, got: %q", errBuf.String())
	}
}

func TestDlqList_truncationHint(t *testing.T) {
	// When len(items) == effectiveLimit → hint must appear.
	// NOTE: len==limit is a heuristic — it's a deliberately-accepted false-positive
	// when the total result set is exactly `limit` with nothing more behind it.
	// The wording "there may be more" owns this uncertainty.
	t.Run("hint present when at limit", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			items := make([]map[string]any, 5)
			for i := range items {
				items[i] = map[string]any{"id": fmt.Sprintf("d%d", i)}
			}
			_ = json.NewEncoder(w).Encode(items)
		}))
		defer srv.Close()
		setupCfg(t, srv.URL)

		cmd := NewRootCmd()
		out := new(bytes.Buffer)
		errBuf := new(bytes.Buffer)
		cmd.SetOut(out)
		cmd.SetErr(errBuf)
		cmd.SetArgs([]string{"dlq", "list", "--limit", "5"})
		if err := cmd.Execute(); err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(errBuf.String(), "there may be more") {
			t.Errorf("expected truncation hint in stderr, got: %q", errBuf.String())
		}
	})

	// When len(items) < limit → hint must NOT appear
	t.Run("hint absent when below limit", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": "d1"}, {"id": "d2"}})
		}))
		defer srv.Close()
		setupCfg(t, srv.URL)

		cmd := NewRootCmd()
		out := new(bytes.Buffer)
		errBuf := new(bytes.Buffer)
		cmd.SetOut(out)
		cmd.SetErr(errBuf)
		cmd.SetArgs([]string{"dlq", "list", "--limit", "5"})
		if err := cmd.Execute(); err != nil {
			t.Fatal(err)
		}
		if strings.Contains(errBuf.String(), "there may be more") {
			t.Errorf("unexpected truncation hint in stderr: %q", errBuf.String())
		}
	})
}

func TestDlqGet_showsReasonDetailPayload(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/platform/dlq/d3000000-0000-0000-0000-000000000003" {
			t.Errorf("path = %q", r.URL.Path)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "d3000000-0000-0000-0000-000000000003", "skipReason": "ValidationFailed",
			"connectorId": "11111111-2222-3333-4444-555555555555", "connectorName": "orders-csv",
			"errorMessage": "amount < 0", "skipDetail": `{"violations":["amount < 0"]}`,
			"payload": `{"amount":-5}`,
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "get", "d3000000-0000-0000-0000-000000000003"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	// Connector: the name for the reader AND the full id for the script.
	for _, want := range []string{"ValidationFailed", "amount < 0", `"amount":-5`, "violations",
		"Connector: orders-csv (11111111-2222-3333-4444-555555555555)"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in: %s", want, out)
		}
	}
}

// -o being a persistent root flag, `dlq get` accepted -o json and ignored it, always printing
// the aligned card (#532).
func TestDlqGetHonoursJson(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "d3000000-0000-0000-0000-000000000003", "skipReason": "ValidationFailed",
			"connectorId": "11111111-2222-3333-4444-555555555555", "connectorName": "orders-csv",
			"errorMessage": "amount < 0", "attempts": 2,
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "get", "d3000000-0000-0000-0000-000000000003", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var got map[string]any
	if err := json.Unmarshal(buf.Bytes(), &got); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if got["skipReason"] != "ValidationFailed" || got["connectorName"] != "orders-csv" {
		t.Fatalf("the entry lost its shape: %v", got)
	}
}

// Registry case: the hub's POST /dlq/{id}/replayed takes an optional ?newRunId= linking
// the entry to the run that replayed it — the CLI never passed it.
func TestDlqReplayed_sendsNewRunId(t *testing.T) {
	var gotPath, gotNewRunID string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotNewRunID = r.URL.Query().Get("newRunId")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "replayed", "d4200000-0000-0000-0000-000000000042", "--new-run-id", "r-99"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/api/platform/dlq/d4200000-0000-0000-0000-000000000042/replayed" {
		t.Errorf("path = %q", gotPath)
	}
	if gotNewRunID != "r-99" {
		t.Errorf("newRunId sent = %q, want r-99", gotNewRunID)
	}
}

func TestDlqReplayed_withoutFlag_omitsNewRunId(t *testing.T) {
	var rawQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rawQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	cmd.SetOut(new(bytes.Buffer))
	cmd.SetErr(new(bytes.Buffer))
	cmd.SetArgs([]string{"dlq", "replayed", "d4200000-0000-0000-0000-000000000042"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(rawQuery, "newRunId") {
		t.Errorf("newRunId must be omitted when the flag is absent, got query %q", rawQuery)
	}
}

func TestDlqList_connectorFilter_resolves36CharName(t *testing.T) {
	// A connector name has no pattern constraint, so a 36-char name must still be resolved — not
	// mistaken for a UUID and forwarded raw (the length-only check bug Copilot caught).
	name := "orders" + strings.Repeat("x", 30) // exactly 36 chars, not a UUID
	if len(name) != 36 {
		t.Fatalf("test setup: name is %d chars, want 36", len(name))
	}
	connID := "bbbbbbbb-1111-2222-3333-444444444444"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/api/platform/connectors"):
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": connID, "name": name}})
		case r.URL.Path == "/api/platform/dlq":
			if got := r.URL.Query().Get("connector"); got != connID {
				t.Errorf("dlq connector param = %q, want the resolved id %q (name not passed through)", got, connID)
			}
			_ = json.NewEncoder(w).Encode([]map[string]any{{"id": "d1", "connectorId": connID}})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "list", "--connector", name})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "d1") {
		t.Errorf("unexpected: %s", buf.String())
	}
}
