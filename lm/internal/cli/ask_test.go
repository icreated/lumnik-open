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

// An abstention that names another door renders THIS client's gesture for it.
//
// The hub's sentence deliberately names no command: the same string reaches `lm ask`, the PWA and
// anything else on the API, so a backtick-quoted CLI command would send a métier on a phone to a
// terminal they do not have (#494/#495). The structured `otherDoors` is what lets each client
// render its own — here, the two commands that exist in this binary (#515).
func TestAsk_anAbstentionRendersTheGestureThisClientHas(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"answer":     "I can't answer that from the data available for this scope — …",
			"sql":        "",
			"rowCount":   0,
			"rows":       []map[string]any{},
			"otherDoors": []string{"lifecycle", "conflicts"},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"ask", "--scope", "magasin", "pourquoi la commande ne peut pas être clôturée ?"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"lm chat", "lm conflicts list"} {
		if !strings.Contains(buf.String(), want) {
			t.Errorf("missing the gesture %q in:\n%s", want, buf.String())
		}
	}
}

// A token this build has no gesture for is silence, never an invented command — and an answer
// that carries none adds nothing at all.
func TestAsk_anUnknownDoorTokenIsIgnored(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"answer": "I can't answer that.", "sql": "", "rowCount": 0,
			"rows": []map[string]any{}, "otherDoors": []string{"a-door-from-a-newer-hub"},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"ask", "--scope", "magasin", "une question"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(buf.String(), "a-door-from-a-newer-hub") {
		t.Errorf("an unmapped token must not be printed raw:\n%s", buf.String())
	}
	if strings.Contains(buf.String(), "lm ") {
		t.Errorf("no gesture was known, so none must be offered:\n%s", buf.String())
	}
}
