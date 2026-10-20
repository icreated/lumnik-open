package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/rivo/tview"
)

// LAST APPLIED renders local, minute precision (output.HumanTime) — not the hub's UTC clock
// dressed up in a friendly layout with no .In(time.Local) conversion, which is what this cell
// did before. Expected value computed, not hardcoded, so this does not depend on the machine
// running it being on any particular timezone.
func TestMigrationsRefresh_lastAppliedIsLocal(t *testing.T) {
	applied, err := time.Parse(time.RFC3339, "2026-05-24T09:12:00Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[{"schema":"core","currentVersion":"V42","pending":0,
			"lastApplied":"2026-05-24T09:12:00Z"}]`))
	}))
	defer srv.Close()

	m := NewMigrations(nil, client.New(config.Context{Server: srv.URL}))
	m.Refresh()

	want := applied.In(time.Local).Format("2006-01-02 15:04")
	if got := m.table.GetCell(1, 3).Text; got != want {
		t.Errorf("LAST APPLIED cell = %q, want %q", got, want)
	}
}

// The hub offers no door to apply a migration (#1136, #1142): it runs them at startup only. A pending
// one is said where it is seen, with the one thing that applies it — never a key that would 404.
func TestMigrationsRefresh_pendingSaysTheHubAppliesThemAtStart(t *testing.T) {
	for _, c := range []struct {
		body string
		want string
	}{
		{`[{"schema":"core","currentVersion":"69","pending":2,"lastApplied":"2026-10-01T10:00:00Z"},
			{"schema":"identity","currentVersion":"12","pending":0,"lastApplied":"2026-10-01T10:00:00Z"},
			{"schema":"rag","currentVersion":"7","pending":1,"lastApplied":"2026-10-01T10:00:00Z"}]`,
			"3 pending (core, rag) — the hub applies them when it next starts"},
		{`[{"schema":"core","currentVersion":"69","pending":0,"lastApplied":"2026-10-01T10:00:00Z"}]`, " :migrations "},
	} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(c.body))
		}))
		m := NewMigrations(nil, client.New(config.Context{Server: srv.URL}))
		m.Refresh()
		srv.Close()
		if got := m.table.GetTitle(); !strings.Contains(got, c.want) {
			t.Errorf("title = %q, want %q", got, c.want)
		}
	}
}

// A schema with no applied migration comes as "lastApplied": "" (#1149), or without the field: the
// page must still list it, LAST APPLIED reading "never" as an em dash — not an ERROR row in place of
// every schema, on the fresh hub where the status matters most.
func TestMigrationsRefresh_neverAppliedRendersADash(t *testing.T) {
	for name, body := range map[string]string{
		"empty":  `[{"schema":"rag","currentVersion":"","pending":3,"lastApplied":""}]`,
		"absent": `[{"schema":"rag","currentVersion":"","pending":3}]`,
	} {
		t.Run(name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = w.Write([]byte(body))
			}))
			defer srv.Close()

			m := NewMigrations(nil, client.New(config.Context{Server: srv.URL}))
			m.Refresh()

			if got := m.table.GetCell(1, 0).Text; got != "rag" {
				t.Fatalf("SCHEMA cell = %q, want rag", got)
			}
			if got := m.table.GetCell(1, 3).Text; got != "—" {
				t.Errorf("LAST APPLIED cell = %q, want —", got)
			}
		})
	}
}

// LAST APPLIED is the hub's string, and one that is not a date is shown verbatim: it is data, so a
// colour tag in it must reach the cell escaped, never styling it (#1153 review).
func TestMigrationsRefresh_lastAppliedIsEscaped(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[{"schema":"core","currentVersion":"69","pending":0,"lastApplied":"[red]x"}]`))
	}))
	defer srv.Close()
	m := NewMigrations(nil, client.New(config.Context{Server: srv.URL}))
	m.Refresh()
	if got, want := m.table.GetCell(1, 3).Text, tview.Escape("[red]x"); got != want {
		t.Errorf("LAST APPLIED cell = %q, want %q", got, want)
	}
}
