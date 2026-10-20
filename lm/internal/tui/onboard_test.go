package tui

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// answers is a Prompt that replies in order and records whether each question was masked.
type answers struct {
	replies []string
	masked  []bool
}

func (a *answers) ask(_ string, masked bool, done func(string)) {
	a.masked = append(a.masked, masked)
	r := a.replies[0]
	a.replies = a.replies[1:]
	done(r)
}

// The JDBC onboarding runs in lm ui (#1117): name, URL, user, a masked password, a schema; then
// test, discover, pick in the in-app picker (an unsupported table is listed, not selectable), and add.
func TestOnboardJdbc(t *testing.T) {
	var bodies = map[string]map[string]any{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		bodies[r.URL.Path] = b
		switch {
		case strings.HasSuffix(r.URL.Path, "/test"):
			_, _ = w.Write([]byte(`{"ok":true,"dialect":"PostgreSQL","version":"16","latencyMs":3}`))
		case strings.HasSuffix(r.URL.Path, "/discover"):
			_, _ = w.Write([]byte(`{"tables":[{"schema":"erp","name":"orders","rowEstimate":10,"suggestedWatermark":"updated"},
				{"schema":"erp","name":"blobs","unsupportedColumns":["data: bytea"]}]}`))
		default:
			_, _ = w.Write([]byte(`{"created":[{"connectorName":"erp-orders"}],"skipped":[]}`))
		}
	}))
	defer srv.Close()

	// A JDBC URL can carry credentials of its own: it must not reach the screen either (#1130 review).
	a := &answers{replies: []string{"erp", "jdbc:postgresql://db/erp?password=hunter2", "reader", "s3cr3t", ""}}
	var offered []client.JdbcTable
	var said []string
	o := onboarding{
		client: client.New(config.Context{Server: srv.URL}),
		ask:    a.ask,
		pick: func(tables []client.JdbcTable, done func([]client.JdbcTable)) {
			offered = tables
			done(tables[:1])
		},
		say:  func(m string) { said = append(said, m) },
		fail: func(m string) { t.Errorf("failed: %s", m) },
		done: func(page string) { said = append(said, "→ "+page) },
	}
	o.jdbc()

	if len(a.masked) != 5 || !a.masked[3] || a.masked[0] {
		t.Errorf("masked = %v, want only the password masked", a.masked)
	}
	add := bodies["/api/platform/sources/jdbc/add"]
	if add == nil || add["sourceName"] != "erp" || add["schema"] != "public" || add["password"] != "s3cr3t" {
		t.Fatalf("add body = %v (all bodies %v)", add, bodies)
	}
	tables, _ := add["tables"].([]any)
	if len(tables) != 1 || tables[0].(map[string]any)["watermark"] != "updated" {
		t.Errorf("tables = %v", tables)
	}
	if len(offered) != 2 {
		t.Errorf("the picker was offered %d tables, want both (the unsupported one shown, not selectable)", len(offered))
	}
	joined := strings.Join(said, "|")
	for _, want := range []string{"PostgreSQL", "erp-orders", "→ sources"} {
		if !strings.Contains(joined, want) {
			t.Errorf("said %q, missing %q", joined, want)
		}
	}
	if strings.Contains(joined, "s3cr3t") || strings.Contains(joined, "hunter2") {
		t.Errorf("a credential reached the screen: %q", joined)
	}
}

// A one-shot CSV ingest of a local file runs in lm ui too (#1117).
func TestOnboardCsv(t *testing.T) {
	var path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.Path
		_, _ = w.Write([]byte(`{"tableName":"t_clients","rowsRead":3,"rowsWritten":3}`))
	}))
	defer srv.Close()
	file := filepath.Join(t.TempDir(), "clients.csv")
	if err := os.WriteFile(file, []byte("code,name\n1,a\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	var said []string
	o := onboarding{
		client: client.New(config.Context{Server: srv.URL}),
		ask:    (&answers{replies: []string{file, "", "clients"}}).ask,
		say:    func(m string) { said = append(said, m) },
		fail:   func(m string) { t.Errorf("failed: %s", m) },
		done:   func(string) {},
	}
	o.csv()
	if path != "/api/platform/sources/csv/ingest" || !strings.Contains(strings.Join(said, "|"), "t_clients") {
		t.Errorf("path %q, said %q", path, said)
	}
}

// The one table picker (#1130 simplify): its [ ]/[x]/[!] marks are text, not colour tags — the CLI
// picker drew them raw — Space never ticks an unsupported table, and Esc reports no confirmation.
func TestTablePicker(t *testing.T) {
	wm := "updated"
	tables := []client.JdbcTable{{Schema: "erp", Name: "orders", SuggestedWatermark: &wm}, {Schema: "erp", Name: "blobs", UnsupportedColumns: []string{"data: bytea"}}}
	var picked []client.JdbcTable
	var confirmed bool
	list := newTablePicker(tables, "", func(p []client.JdbcTable, ok bool) { picked, confirmed = p, ok })
	if main, _ := list.GetItemText(0); !strings.HasPrefix(main, "[ []") {
		t.Errorf("label %q is not escaped", main)
	}
	key := func(k tcell.Key, r rune) { list.InputHandler()(tcell.NewEventKey(k, r, tcell.ModNone), func(tview.Primitive) {}) }
	key(tcell.KeyRune, ' ') // orders
	list.SetCurrentItem(1)
	key(tcell.KeyRune, ' ') // blobs: unsupported, ignored
	key(tcell.KeyEscape, 0)
	if confirmed || len(picked) != 1 || picked[0].Name != "orders" {
		t.Errorf("picked %v confirmed %v", picked, confirmed)
	}
}
