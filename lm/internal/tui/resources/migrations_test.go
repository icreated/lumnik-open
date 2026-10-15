package resources

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
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
