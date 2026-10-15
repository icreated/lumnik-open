package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
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

// askServer stands up a stub hub that answers every ask with payload, and points lm's config at it.
func askServer(t *testing.T, payload map[string]any) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(payload)
	}))
	t.Cleanup(srv.Close)
	setupCfg(t, srv.URL)
}

func askWith(t *testing.T, payload map[string]any, args ...string) string {
	t.Helper()
	askServer(t, payload)
	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs(append([]string{"ask"}, args...))
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

func TestAsk_aDisputedRecordIsPrintedBeforeTheSQL(t *testing.T) {
	out := askWith(t, map[string]any{
		"answer": "Il y a 2 clients GOLD à Lyon.", "sql": "SELECT COUNT(*) FROM v WHERE city = 'Lyon'",
		"rowCount": 1, "rows": []map[string]any{{"count": 2}}, "otherDoors": []string{"conflicts"},
		"disputed": []map[string]any{
			{"key": "P-1008", "column": "city", "label": "Ville", "matched": "Lyon", "retained": "Lyon", "counted": true,
				"sources": []map[string]any{{"src": "t_erp", "value": "Lyon"}, {"src": "t_crm", "value": "Villeurbanne"}}},
			{"key": "P-1002", "column": "city", "label": "Ville", "matched": "Milan", "retained": "Milano", "counted": false,
				"sources": []map[string]any{{"src": "t_erp", "value": "Milano"}, {"src": "t_crm", "value": nil}}},
		},
		"disputedTruncated": true,
	}, "--scope", "desk", "combien ?")
	for _, want := range []string{
		"disputed — a source disagrees on a value this answer filtered on:",
		"P-1008  city = 'Lyon'  counted  t_erp: Lyon · t_crm: Villeurbanne",
		"P-1002  city = 'Milan'  not counted  t_erp: Milano · t_crm: (empty)",
		"… and more — lm conflicts list",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	if strings.Index(out, "disputed —") > strings.Index(out, "SQL:") {
		t.Errorf("the disputed block must come before the SQL:\n%s", out)
	}
}

func TestAsk_noDisputedBlockWhenTheListIsEmpty(t *testing.T) {
	out := askWith(t, map[string]any{"answer": "2.", "sql": "SELECT 1", "rowCount": 1,
		"rows": []map[string]any{{"x": 1}}, "disputed": []any{}}, "combien ?")
	if strings.Contains(out, "disputed") {
		t.Errorf("an empty list must print nothing:\n%s", out)
	}
}

// `lm ask … > out.txt` keeps the whole block — the answer, every line and the blank separators, in
// order — and stderr stays empty (#884). Through cli.Run, the binary's wiring, this held before #884
// too: Run sets the root's writer, and cobra's cmd.Println follows it. On cobra's defaults
// cmd.Println falls back to the process's stderr, so that subtest is what keeps ask.go on one writer.
func TestAsk_theWholeBlockGoesToStdoutInOrder(t *testing.T) {
	askServer(t, map[string]any{
		"answer": "Il y a 2 clients GOLD à Lyon.", "sql": "SELECT COUNT(*) FROM v WHERE city = 'Lyon'",
		"rowCount": 1, "rows": []map[string]any{{"count": 2}}, "dataAsOf": "2026-08-20T06:05:00Z",
		"otherDoors": []string{"conflicts"},
		"disputed": []map[string]any{{"key": "P-1008", "column": "city", "matched": "Lyon", "counted": true,
			"sources": []map[string]any{{"src": "t_erp", "value": "Lyon"}, {"src": "t_crm", "value": "Villeurbanne"}}}},
	})
	args := []string{"ask", "--scope", "desk", "combien ?"}
	want := "Il y a 2 clients GOLD à Lyon.\n" +
		"  data as of: 2026-08-20T06:05:00Z (last completed run)\n" +
		"\n" +
		"  disputed — a source disagrees on a value this answer filtered on:\n" +
		"    P-1008  city = 'Lyon'  counted  t_erp: Lyon · t_crm: Villeurbanne\n" +
		"\n" +
		"  SQL: SELECT COUNT(*) FROM v WHERE city = 'Lyon'\n" +
		"\n" +
		"  try: lm conflicts list --scope desk  — where this scope has source conflicts or lookup ambiguities, row by row\n"

	t.Run("through cli.Run", func(t *testing.T) {
		stdout, stderr, code := runCmd(t, args...)
		require.Zero(t, code, stderr)
		assert.Equal(t, want, stdout)
		assert.Empty(t, stderr)
	})

	t.Run("on cobra's defaults", func(t *testing.T) {
		root := NewRootCmd()
		root.SetArgs(args)
		stdout, stderr := captureProcessOutput(t, func() {
			if err := root.Execute(); err != nil {
				t.Error(err)
			}
		})
		assert.Equal(t, want, stdout)
		assert.Empty(t, stderr)
	})
}

// captureProcessOutput runs fn with os.Stdout and os.Stderr pointed at files and returns what each got.
// It swaps process-wide globals: no test in this package runs in parallel.
func captureProcessOutput(t *testing.T, fn func()) (stdout, stderr string) {
	t.Helper()
	dir := t.TempDir()
	outF, err := os.Create(filepath.Join(dir, "stdout"))
	require.NoError(t, err)
	errF, err := os.Create(filepath.Join(dir, "stderr"))
	require.NoError(t, err)
	origOut, origErr := os.Stdout, os.Stderr
	os.Stdout, os.Stderr = outF, errF
	fn()
	os.Stdout, os.Stderr = origOut, origErr
	require.NoError(t, outF.Close())
	require.NoError(t, errF.Close())
	o, err := os.ReadFile(outF.Name())
	require.NoError(t, err)
	e, err := os.ReadFile(errF.Name())
	require.NoError(t, err)
	return string(o), string(e)
}
