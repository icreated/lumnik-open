package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// lm ask is the analytic (text-to-SQL) entry point — it must send mode=analytic
// and surface the answer plus the SQL that ran (honesty: show what was executed).
// Semantic/citation chat lives in `lm chat` and `lm rag search`.
func TestAskUsesAnalyticModeAndShowsSQL(t *testing.T) {
	var gotMode string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		gotMode, _ = body["mode"].(string)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"answer":   "There is 1 client in Lyon.",
			"sql":      "SELECT COUNT(*) FROM connector.v_customer WHERE city = 'Lyon'",
			"rowCount": 1,
			"rows":     []map[string]any{{"count": 1}},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"ask", "--scope", "clients", "combien de clients à Lyon ?"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if gotMode != "analytic" {
		t.Errorf("expected mode=analytic, got %q", gotMode)
	}
	if !strings.Contains(buf.String(), "1 client in Lyon") {
		t.Errorf("answer missing: %s", buf.String())
	}
	if !strings.Contains(buf.String(), "SELECT COUNT(*)") {
		t.Errorf("SQL not shown: %s", buf.String())
	}
}

func TestAskMultiWordQuestion(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		question := body["question"].(string)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"answer":   "Echo: " + question,
			"sql":      "SELECT 1",
			"rowCount": 0,
			"rows":     []map[string]any{},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"ask", "show", "me", "top", "5", "customers"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "show me top 5 customers") {
		t.Errorf("multi-word join failed: %s", buf.String())
	}
}
