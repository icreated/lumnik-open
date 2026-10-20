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
	// Neither lm chat nor lm conflicts list takes --scope: a hint carrying it is an unknown-flag error.
	if strings.Contains(buf.String(), "--scope") {
		t.Errorf("a gesture must not offer a flag its command does not have:\n%s", buf.String())
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
		"  COUNT\n" +
		"  2\n" +
		"  1 row returned\n" +
		"\n" +
		"  SQL: SELECT COUNT(*) FROM v WHERE city = 'Lyon'\n" +
		"\n" +
		"  try: lm conflicts list  — where this scope has source conflicts or lookup ambiguities, row by row\n"

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

// #1105: the rows the hub returned are tabulated between what qualifies the answer and the SQL. A
// withheld answer says "the rows and the SQL below are the answer": the rows must be there to read.
func TestAsk_aWithheldAnswerShowsTheRowsItPointsTo(t *testing.T) {
	askServer(t, map[string]any{
		"answer": "La réponse rédigée citait un chiffre que ces lignes ne confirment pas ; elle est donc retenue. " +
			"Les lignes et le SQL ci-dessous font foi.",
		"sql":      "SELECT v.reference, v.description FROM v WHERE c.name = 'Pompes' AND v.description ILIKE '%Badu%'\nLIMIT 500",
		"rowCount": 2, "abstained": true, "dataAsOf": "2026-10-16T08:00:00Z",
		"rows": []json.RawMessage{ // raw: a Go map would sort the keys, and the hub sends them in the query's order
			json.RawMessage(`{"reference":"2195.300.037","description":"Pompe Badu Resort"}`),
			json.RawMessage(`{"reference":"2195.300.041","description":"Pompe Badu Prime 40"}`),
		},
	})
	stdout, stderr, code := runCmd(t, "ask", "--scope", "pwa-chat", "--lang", "fr",
		"Donne-moi les références de la catégorie Pompes dont la description contient Badu.")
	require.Zero(t, code, stderr)
	assert.Equal(t, "La réponse rédigée citait un chiffre que ces lignes ne confirment pas ; elle est donc retenue. "+
		"Les lignes et le SQL ci-dessous font foi.\n"+
		"  data as of: 2026-10-16T08:00:00Z (last completed run)\n"+
		"\n"+
		"  REFERENCE     DESCRIPTION\n"+
		"  2195.300.037  Pompe Badu Resort\n"+
		"  2195.300.041  Pompe Badu Prime 40\n"+
		"  2 rows returned\n"+
		"\n"+
		"  SQL: SELECT v.reference, v.description FROM v WHERE c.name = 'Pompes' AND v.description ILIKE '%Badu%'\n"+
		"LIMIT 500\n", stdout)
	assert.Empty(t, stderr)
}

// Zero rows is said in words, never as a header over nothing; an answer that ran no SQL has no result to count.
func TestAsk_zeroRowsAreSaidWithoutATable(t *testing.T) {
	t.Run("a query that returned none", func(t *testing.T) {
		out := askWith(t, map[string]any{"answer": "La requête n'a renvoyé aucune ligne.", "sql": "SELECT name FROM v",
			"rowCount": 0, "rows": []any{}, "abstained": true}, "qui ?")
		assert.Equal(t, "La requête n'a renvoyé aucune ligne.\n\n  no rows returned\n\n  SQL: SELECT name FROM v\n", out)
	})
	t.Run("no query at all", func(t *testing.T) {
		out := askWith(t, map[string]any{"answer": "Je ne peux pas répondre.", "sql": "",
			"rowCount": 0, "rows": []any{}, "abstained": true}, "pourquoi ?")
		assert.Equal(t, "Je ne peux pas répondre.\n", out)
	})
}

// The count line is the hub's rowCount next to what was printed: they agree today, and if they ever do
// not, the line says both rather than letting a page pass for the whole result.
func TestAsk_aCountTheRowsDoNotReachIsStated(t *testing.T) {
	out := askWith(t, map[string]any{"answer": "5.", "sql": "SELECT n FROM v", "rowCount": 5,
		"rows": []map[string]any{{"n": 1}, {"n": 2}, {"n": 3}}}, "combien ?")
	assert.Contains(t, out, "  3\n  3 rows shown; the hub counted 5\n")
}

// A tab or a line break inside a value would push it under another column's header, or start a row that
// is not one: it is printed as its escape.
func TestAsk_aValueCannotShiftIntoAnotherColumn(t *testing.T) {
	out := askWith(t, map[string]any{"answer": "1.", "sql": "SELECT a, b FROM v", "rowCount": 1,
		"rows": []map[string]any{{"a": "x\ty\nz\r", "b": "w"}}}, "quoi ?")
	assert.Contains(t, out, "  A          B\n  x\\ty\\nz\\r  w\n  1 row returned\n")
}

// #1105: -o json and -o yaml print the whole analytic result as ONE document — the rows as the hub typed
// them (a number stays a number, the query's column order), the honesty fields — and nothing around it.
func TestAsk_jsonAndYamlAreOneDocument(t *testing.T) {
	payload := map[string]any{
		"answer": "La réponse rédigée est retenue.", "sql": "SELECT ref, stock, note FROM v\nLIMIT 500",
		"rowCount": 1, "rows": []json.RawMessage{json.RawMessage(`{"ref":"A-1","stock":12,"note":null}`)},
		"dataAsOf": "2026-10-16T08:00:00Z", "otherDoors": []string{"conflicts"}, "abstained": true,
		"disputed": []map[string]any{{"key": "P-1", "column": "city", "label": "Ville", "matched": "Lyon",
			"retained": "Lyon", "counted": true, "sources": []map[string]any{{"src": "t_erp", "value": "Lyon"}}}},
		"disputedTruncated": false,
	}
	t.Run("json", func(t *testing.T) {
		askServer(t, payload)
		stdout, stderr, code := runCmd(t, "ask", "-o", "json", "--scope", "desk", "quoi ?")
		require.Zero(t, code, stderr)
		assert.Equal(t, `{
  "answer": "La réponse rédigée est retenue.",
  "sql": "SELECT ref, stock, note FROM v\nLIMIT 500",
  "rowCount": 1,
  "columns": [
    "ref",
    "stock",
    "note"
  ],
  "rows": [
    {
      "ref": "A-1",
      "stock": 12,
      "note": null
    }
  ],
  "dataAsOf": "2026-10-16T08:00:00Z",
  "otherDoors": [
    "conflicts"
  ],
  "disputed": [
    {
      "key": "P-1",
      "column": "city",
      "label": "Ville",
      "matched": "Lyon",
      "retained": "Lyon",
      "counted": true,
      "sources": [
        {
          "src": "t_erp",
          "value": "Lyon"
        }
      ]
    }
  ],
  "disputedTruncated": false,
  "abstained": true
}
`, stdout)
		assert.Empty(t, stderr)
	})
	t.Run("yaml", func(t *testing.T) {
		askServer(t, payload)
		stdout, stderr, code := runCmd(t, "ask", "-o", "yaml", "--scope", "desk", "quoi ?")
		require.Zero(t, code, stderr)
		assert.Equal(t, `answer: La réponse rédigée est retenue.
sql: |-
    SELECT ref, stock, note FROM v
    LIMIT 500
rowCount: 1
columns:
    - ref
    - stock
    - note
rows:
    - ref: A-1
      stock: 12
      note: null
dataAsOf: "2026-10-16T08:00:00Z"
otherDoors:
    - conflicts
disputed:
    - key: P-1
      column: city
      label: Ville
      matched: Lyon
      retained: Lyon
      counted: true
      sources:
        - src: t_erp
          value: Lyon
disputedTruncated: false
abstained: true
`, stdout)
		assert.Empty(t, stderr)
	})
}

// An ask costs a model call: a format lm cannot print is refused before the hub is asked, not after.
func TestAsk_anUnknownOutputFormatIsRefusedBeforeAsking(t *testing.T) {
	asked := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = true
		_ = json.NewEncoder(w).Encode(map[string]any{"answer": "1.", "sql": "SELECT 1", "rowCount": 0, "rows": []any{}})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	_, stderr, code := runCmd(t, "ask", "-o", "csv", "combien ?")
	assert.NotZero(t, code)
	assert.Contains(t, stderr, "unknown output format: csv (want table|json|yaml)")
	assert.False(t, asked, "the hub must not be asked for an answer lm cannot print")
}

// A null in the last column is an empty cell, not padding: no line of the table ends in spaces.
func TestAsk_anEmptyLastCellLeavesNoTrailingSpaces(t *testing.T) {
	out := askWith(t, map[string]any{"answer": "Retenue.", "sql": "SELECT reference, total FROM v", "rowCount": 1,
		"rows": []json.RawMessage{json.RawMessage(`{"reference":"2195.300.037","total":null}`)}}, "quoi ?")
	assert.Contains(t, out, "  REFERENCE     TOTAL\n  2195.300.037\n  1 row returned\n")
}

// #1105 review: the document carries the hub's text as written — a `<>` in the SQL, a `&` in the answer or in a cell
// (a jsonb one included) — never their &-style escapes.
func TestAsk_jsonKeepsHTMLCharactersAsWritten(t *testing.T) {
	askServer(t, map[string]any{
		"answer": "Dupont & Fils.", "sql": "SELECT name, note FROM v WHERE segment <> 'GOLD'", "rowCount": 1,
		"rows": []json.RawMessage{json.RawMessage(`{"name":"Dupont & Fils","note":{"text":"<b> & c"}}`)},
	})
	stdout, stderr, code := runCmd(t, "ask", "-o", "json", "qui ?")
	require.Zero(t, code, stderr)
	for _, want := range []string{`"answer": "Dupont & Fils."`, `segment <> 'GOLD'`, `"name": "Dupont & Fils"`, `"text": "<b> & c"`} {
		assert.Contains(t, stdout, want)
	}
	assert.NotContains(t, stdout, `\u00`)
}

// The hub sends "dataAsOf": null when the scope has no completed run (a CSV ingested one-shot, a refusal): that is
// "unknown", read as such and printed back as null — never an error (#1105 review).
func TestAsk_aNullFreshnessIsUnknownNotAnError(t *testing.T) {
	askServer(t, map[string]any{"answer": "1.", "sql": "SELECT 1", "rowCount": 1, "dataAsOf": nil,
		"rows": []json.RawMessage{json.RawMessage(`{"n":1}`)}})
	stdout, stderr, code := runCmd(t, "ask", "-o", "json", "combien ?")
	require.Zero(t, code, stderr)
	assert.Contains(t, stdout, `"dataAsOf": null`)
	out := askWith(t, map[string]any{"answer": "1.", "sql": "SELECT 1", "rowCount": 1, "dataAsOf": nil,
		"rows": []json.RawMessage{json.RawMessage(`{"n":1}`)}}, "combien ?")
	assert.NotContains(t, out, "data as of")
}
