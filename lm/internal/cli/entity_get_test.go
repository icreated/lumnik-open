package cli

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// runEntityGet drives `lm entity get` against a stub hub and returns stdout, stderr and the error.
func runEntityGet(t *testing.T, h http.HandlerFunc, args ...string) (string, string, error) {
	t.Helper()
	srv := httptest.NewServer(h)
	defer srv.Close()
	setupCfg(t, srv.URL)
	cmd := NewRootCmd()
	out, errBuf := new(bytes.Buffer), new(bytes.Buffer)
	cmd.SetOut(out)
	cmd.SetErr(errBuf)
	cmd.SetArgs(append([]string{"entity", "get"}, args...))
	err := cmd.Execute()
	return out.String(), errBuf.String(), err
}

const twoRows = `[{"product_id":"1024944","name":"Pool pump","category":"Pumps","_conflicts":"{}"},` +
	`{"product_id":"1024945","name":"Filter","category":"Pumps","_conflicts":"{}"}]`

func serveRows(body string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) { _, _ = fmt.Fprint(w, body) }
}

func TestEntityGet_buildsRequest(t *testing.T) {
	var path, raw string
	var q map[string][]string
	_, _, err := runEntityGet(t, func(w http.ResponseWriter, r *http.Request) {
		path, raw, q = r.Method+" "+r.URL.Path, r.URL.EscapedPath(), r.URL.Query()
		_, _ = fmt.Fprint(w, `[]`)
	}, "Product With/Category", "--limit", "25", "--offset", "50", "--filter", "product_id=1024944", "--filter", "name=a&b=c")
	if err != nil {
		t.Fatal(err)
	}
	if path != "GET /api/entities/Product With/Category" || raw != "/api/entities/Product%20With%2FCategory" {
		t.Errorf("path = %q (escaped %q)", path, raw)
	}
	want := map[string]string{"limit": "25", "offset": "50", "product_id": "1024944", "name": "a&b=c"}
	for k, v := range want {
		if len(q[k]) != 1 || q[k][0] != v {
			t.Errorf("query %s = %v, want exactly [%s]", k, q[k], v)
		}
	}
}

func TestEntityGet_defaultsToFirstPageOf100(t *testing.T) {
	var q map[string][]string
	_, _, err := runEntityGet(t, func(w http.ResponseWriter, r *http.Request) {
		q = r.URL.Query()
		_, _ = fmt.Fprint(w, `[]`)
	}, "E")
	if err != nil {
		t.Fatal(err)
	}
	if q["limit"][0] != "100" || q["offset"][0] != "0" {
		t.Errorf("defaults = limit %v offset %v", q["limit"], q["offset"])
	}
}

func TestEntityGet_filterValueMayContainEquals(t *testing.T) {
	var q map[string][]string
	_, _, err := runEntityGet(t, func(w http.ResponseWriter, r *http.Request) {
		q = r.URL.Query()
		_, _ = fmt.Fprint(w, `[]`)
	}, "E", "--filter", "expr=a=b", "--filter", "empty=")
	if err != nil {
		t.Fatal(err)
	}
	if q["expr"][0] != "a=b" || len(q["empty"]) != 1 || q["empty"][0] != "" {
		t.Errorf("query = %v", q)
	}
}

func TestEntityGet_table(t *testing.T) {
	out, errOut, err := runEntityGet(t, serveRows(twoRows), "E")
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimRight(out, "\n"), "\n")
	if len(lines) != 3 {
		t.Fatalf("want header + 2 rows, got %q", out)
	}
	if f := strings.Fields(lines[0]); strings.Join(f, " ") != "PRODUCT_ID NAME CATEGORY _CONFLICTS" {
		t.Errorf("header = %q (hub order, upper-cased)", lines[0])
	}
	if !strings.Contains(lines[1], "1024944") || !strings.Contains(lines[1], "Pool pump") {
		t.Errorf("row 1 = %q", lines[1])
	}
	if errOut != "" {
		t.Errorf("a short page says nothing on stderr, got %q", errOut)
	}
}

func TestEntityGet_jsonAndYamlKeepHubOrder(t *testing.T) {
	out, _, err := runEntityGet(t, serveRows(`[{"b":1,"a":"x"}]`), "E", "-o", "json")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Index(out, `"b"`) > strings.Index(out, `"a"`) || !strings.Contains(out, `"b": 1`) {
		t.Errorf("json = %s", out)
	}
	out, _, err = runEntityGet(t, serveRows(`[{"b":1,"a":"x"}]`), "E", "-o", "yaml")
	if err != nil {
		t.Fatal(err)
	}
	if out != "- b: 1\n  a: x\n" {
		t.Errorf("yaml = %q", out)
	}
}

func TestEntityGet_fullPageIsAnnounced(t *testing.T) {
	_, errOut, err := runEntityGet(t, serveRows(twoRows), "E", "--limit", "2")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(errOut, "there may be more") {
		t.Errorf("stderr = %q", errOut)
	}
}

func TestEntityGet_refusesBeforeAskingTheHub(t *testing.T) {
	for _, tc := range []struct {
		name string
		args []string
		want string
	}{
		{"limit zero", []string{"--limit", "0"}, "--limit"},
		{"limit above the server cap", []string{"--limit", "1001"}, "1000"},
		{"negative offset", []string{"--offset", "-1"}, "--offset"},
		{"filter without =", []string{"--filter", "product_id"}, "COLUMN=VALUE"},
		{"filter without a column", []string{"--filter", "=5"}, "COLUMN=VALUE"},
		{"filter named limit", []string{"--filter", "limit=5"}, "limit"},
		{"filter named offset", []string{"--filter", "offset=5"}, "offset"},
		{"filter named identity", []string{"--filter", "identity=true"}, "identity"},
		{"same column twice", []string{"--filter", "a=1", "--filter", "a=2"}, "twice"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			called := false
			_, _, err := runEntityGet(t, func(w http.ResponseWriter, r *http.Request) {
				called = true
				_, _ = fmt.Fprint(w, `[]`)
			}, append([]string{"E"}, tc.args...)...)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Errorf("err = %v, want mention of %q", err, tc.want)
			}
			if called {
				t.Error("the hub must not be asked")
			}
		})
	}
}

// The hub silently ignores a filter that names no canonical column and returns EVERY row — a
// typo would read as "the whole entity matches". A returned row that lacks the column proves it.
func TestEntityGet_refusesAFilterTheHubIgnored(t *testing.T) {
	out, _, err := runEntityGet(t, serveRows(twoRows), "E", "--filter", "prodct_id=1")
	if err == nil || !strings.Contains(err.Error(), "prodct_id") {
		t.Fatalf("err = %v", err)
	}
	if out != "" {
		t.Errorf("rows the filter did not select must not be printed: %q", out)
	}
}

func TestEntityGet_hubErrorsKeepStatusAndMessage(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
		want   []string
	}{
		{404, `{"error":"entity Ghost not found"}`, []string{"entity Ghost not found"}},
		{403, `{"error":"scope not granted"}`, []string{"403", "scope not granted"}},
		{403, `{"error":"missing role lm_user"}`, []string{"403", "missing role lm_user"}},
	} {
		_, _, err := runEntityGet(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = fmt.Fprint(w, tc.body)
		}, "Ghost")
		if err == nil {
			t.Fatalf("%d: want an error", tc.status)
		}
		for _, w := range tc.want {
			if !strings.Contains(err.Error(), w) {
				t.Errorf("%d: err = %q, want %q", tc.status, err, w)
			}
		}
	}
}

// A table has one header: every row is laid out on the FIRST row's columns, whatever order or
// extra keys a later row carries — otherwise cells drift under the wrong header.
func TestEntityGet_tableUsesTheFirstRowsColumnsForEveryRow(t *testing.T) {
	out, _, err := runEntityGet(t, serveRows(`[{"a":"1","b":"2"},{"b":"20","extra":"x","a":"10"}]`), "E")
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimRight(out, "\n"), "\n")
	if len(lines) != 3 || strings.Fields(lines[2])[0] != "10" || strings.Fields(lines[2])[1] != "20" || len(strings.Fields(lines[2])) != 2 {
		t.Errorf("row 2 must read a=10 b=20 under A B, got %q", lines)
	}
}
