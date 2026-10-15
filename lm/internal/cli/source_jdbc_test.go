package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"

	"strings"
	"testing"
)

func TestSourceJdbcSubcommandsRegistered(t *testing.T) {
	cmd := newSourceJdbcCmd()
	got := map[string]bool{}
	for _, sub := range cmd.Commands() {
		got[sub.Name()] = true
	}
	want := []string{"test", "discover", "add", "list-tables"}
	for _, w := range want {
		if !got[w] {
			t.Errorf("expected subcommand %q registered, have %v", w, got)
		}
	}
}

func TestSourceCmdContainsJdbc(t *testing.T) {
	cmd := newSourceCmd()
	got := map[string]bool{}
	for _, sub := range cmd.Commands() {
		got[sub.Name()] = true
	}
	if !got["jdbc"] {
		t.Errorf("source command missing jdbc subcommand, have %v", got)
	}
}

func TestSourceJdbcAddRequiresArgs(t *testing.T) {
	cmd := newSourceJdbcAddCmd()
	if cmd.Args == nil {
		t.Error("expected Args validator on add command")
	}
}

func TestSourceJdbcAddHasSchemaFlag(t *testing.T) {
	f := newSourceJdbcAddCmd().Flags().Lookup("schema")
	if f == nil {
		t.Fatal("add has no --schema flag: an ERP living outside public cannot use the guided flow")
	}
	if f.DefValue != "public" {
		t.Errorf("default schema = %q, want public", f.DefValue)
	}
}

func TestSourceJdbcAddHasSkipPatternFlag(t *testing.T) {
	f := newSourceJdbcAddCmd().Flags().Lookup("skip-pattern")
	if f == nil {
		t.Fatal("add has no --skip-pattern flag: the interactive picker cannot hide an ERP's catalog tables")
	}
	if f.DefValue != "" {
		t.Errorf("default = %q, want empty: the source dialect's own exclusions apply", f.DefValue)
	}
}

func TestSourceJdbcAddLetsTheHubPickTheMode(t *testing.T) {
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&got)
		_, _ = w.Write([]byte(`{"created":[],"skipped":[]}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL) // a temporary ~/.lm context, never the developer's

	root := NewRootCmd()
	root.SetIn(strings.NewReader("p\n"))
	root.SetOut(&strings.Builder{})
	root.SetErr(&strings.Builder{}) // the undeclared-pipe warning, off the test log
	root.SetArgs([]string{"source", "jdbc", "add", "src", "--url", "jdbc:postgresql://h/db", "--user", "u",
		"--tables", "t1"})
	if err := root.Execute(); err != nil {
		t.Fatal(err)
	}
	tables, _ := got["tables"].([]any)
	if len(tables) != 1 {
		t.Fatalf("tables sent: %v", got["tables"])
	}
	if mode, _ := tables[0].(map[string]any)["mode"]; mode != nil && mode != "" {
		t.Errorf("the CLI forced mode %q; the hub decides watermark or full from the table itself", mode)
	}
}

func TestSplitPatternsTrimsAndDropsEmpties(t *testing.T) {
	got := splitPatterns("pg_*, information_schema*,, t_* ")
	want := []string{"pg_*", "information_schema*", "t_*"}
	if len(got) != len(want) {
		t.Fatalf("got %q, want %q", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("pattern %d = %q, want %q", i, got[i], want[i])
		}
	}
}

// The line `add` prints per created connector is transcribed in docs/connectors/jdbc.md, so it is
// pinned here rather than trusted. It used to end with "(mapping <connector>-mapping)", naming a
// connector.connector_mapping row nothing read back and no command could remove — the row whose
// UNIQUE (tenant_id, name) made re-adding a deleted table fail at commit with an anonymous 500.
func TestSourceJdbcAddPrintsOneLinePerCreatedConnector(t *testing.T) {
	var body string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		body = string(b)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"created": []map[string]string{{
				"connectorId":   "00000000-0000-0000-0000-000000000001",
				"connectorName": "demo-src--warehouses",
				"status":        "created",
			}},
			"skipped": []map[string]string{},
		})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	root := NewRootCmd()
	root.SetIn(strings.NewReader("secret\n"))
	root.SetArgs([]string{"source", "jdbc", "add", "demo-src",
		"--url", "jdbc:postgresql://h/d", "--user", "u", "--schema", "demo", "--tables", "warehouses"})
	// stdout and stderr are kept apart on purpose: what is transcribed in the docs is the
	// STDOUT of the command, and this pipe carries no --password-stdin, so the undeclared-pipe
	// warning belongs to the other stream.
	out, errOut := &strings.Builder{}, &strings.Builder{}
	root.SetOut(out)
	root.SetErr(errOut)
	if err := root.Execute(); err != nil {
		t.Fatalf("execute: %v (out=%s)", err, out.String())
	}

	want := "Created 1 connectors:\n  • demo-src--warehouses\n"
	if out.String() != want {
		t.Errorf("output\n got: %q\nwant: %q", out.String(), want)
	}
	if !strings.Contains(errOut.String(), "--password-stdin") {
		t.Errorf("stderr lost the undeclared-pipe warning: %q", errOut.String())
	}
	if strings.Contains(body, "templateMode") {
		t.Errorf("add still sends templateMode — the mapping template it drafted had no reader: %s", body)
	}
}

// The other listing on the generation path: `list-tables` says which tables of a JDBC source
// are already onboarded, and its rows carry more than the three columns it prints
// (connectorId, lastRunAt, rowsIngested). -o being a persistent root flag, asking for that as
// data returned the column-aligned page instead — the same silent ignore as `source schema`
// and `secret list`.
//
// The human rendering is deliberately left byte-for-byte alone: docs/connectors/jdbc.md
// transcribes it.
func TestJdbcListTablesHonoursJson(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"sourceName":"demo-src","tables":[{"table":"warehouses","connectorName":"demo-src--warehouses","connectorId":"c-1","status":"active","rowsIngested":2}]}`))
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs([]string{"source", "jdbc", "list-tables", "demo-src", "-o", "json"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}

	var got struct {
		SourceName string `json:"sourceName"`
		Tables     []struct {
			Table        string `json:"table"`
			Status       string `json:"status"`
			RowsIngested int64  `json:"rowsIngested"`
		} `json:"tables"`
	}
	if err := json.Unmarshal(buf.Bytes(), &got); err != nil {
		t.Fatalf("-o json must emit parseable JSON, got %q: %v", buf.String(), err)
	}
	if got.SourceName != "demo-src" || len(got.Tables) != 1 {
		t.Fatalf("the response lost its shape: %+v", got)
	}
	if got.Tables[0].RowsIngested != 2 {
		t.Errorf("-o json must carry the fields the table does not print: %+v", got.Tables[0])
	}
}
