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
			{"id": "d1", "connectorId": "c1", "runId": "r1", "skipReason": "BAD_RECORD", "errorMessage": "boom", "attempts": 1},
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
	if !strings.Contains(buf.String(), "d1") {
		t.Errorf("unexpected: %s", buf.String())
	}
}

func TestDlqList_runFilter(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("run") != "r-7" {
			t.Errorf("run param = %q, want r-7", r.URL.Query().Get("run"))
		}
		_ = json.NewEncoder(w).Encode([]map[string]any{{"id": "d9", "runId": "r-7", "skipReason": "ValidationFailed"}})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"dlq", "list", "--run", "r-7"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "d9") {
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
	for _, want := range []string{"ValidationFailed", "amount < 0", `"amount":-5`, "violations"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in: %s", want, out)
		}
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
