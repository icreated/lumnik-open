package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// The describe panel's "captured:" line renders local, second precision — not the raw
// RFC3339 the hub sends. Expected value computed, not hardcoded, so this does not depend on
// the machine running it being on any particular timezone.
func TestDLQDescribe_capturedIsLocal(t *testing.T) {
	captured, err := time.Parse(time.RFC3339, "2026-08-15T09:12:04Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	d := &DLQ{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"}),
		items: []types.DeadLetter{
			{ID: "dl1", ConnectorID: "c1", RunID: "r1", SkipReason: "row_error", CapturedAt: captured},
		}}
	d.render()
	d.table.Select(1, 0)
	_, body, ok := d.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	want := "captured:    " + captured.In(time.Local).Format("2006-01-02 15:04:05")
	if !strings.Contains(body, want) {
		t.Errorf("describe body missing %q:\n%s", want, body)
	}
	if strings.Contains(body, "2026-08-15T09:12:04Z") {
		t.Errorf("describe body should render local-formatted, not the raw ISO string:\n%s", body)
	}
}

// The TUI's CONNECTOR cell reads like the runs panel (the name), and describe carries name + id.
func TestDLQ_connectorNameInCellAndDescribe(t *testing.T) {
	d := &DLQ{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"}),
		items: []types.DeadLetter{
			{ID: "dl1", ConnectorID: "11111111-2222-3333-4444-555555555555", ConnectorName: "orders-csv", RunID: "r1", SkipReason: "row_error"},
		}}
	d.render()
	if got := d.table.GetCell(1, 1).Text; got != "orders-csv" {
		t.Errorf("named connector cell = %q, want the name", got)
	}
	d.table.Select(1, 0)
	_, body, ok := d.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	for _, want := range []string{"orders-csv", "11111111-2222-3333-4444-555555555555"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe must carry %q (name for the reader, id for the script):\n%s", want, body)
		}
	}
}

// #1252: a replayed or discarded entry stays in :dlq; its RESOLUTION cell says so, empty while open.
func TestDLQ_resolutionCell(t *testing.T) {
	d := &DLQ{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"}),
		items: []types.DeadLetter{
			{ID: "dl1", RunID: "r1", Resolution: "DISCARDED"},
			{ID: "dl2", RunID: "r1"},
		}}
	d.render()
	if got := d.table.GetCell(0, 5).Text; got != "RESOLUTION" {
		t.Errorf("header = %q, want RESOLUTION", got)
	}
	if got := d.table.GetCell(1, 5).Text; got != "DISCARDED" {
		t.Errorf("handled entry's cell = %q, want DISCARDED", got)
	}
	if got := d.table.GetCell(2, 5).Text; got != "" {
		t.Errorf("open entry's cell = %q, want empty", got)
	}
}

// Errors are fixed from the run that produced them: :dlq can be narrowed to one run or one
// connector, says so in its title, and clears back to everything (#1111).
func TestDLQ_filtersByRunOrConnector(t *testing.T) {
	var queries []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		queries = append(queries, r.URL.RawQuery)
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	d := NewDLQ(nil, client.New(config.Context{Server: srv.URL}), nil)

	d.SetRunFilter("run-1234567890")
	if !strings.Contains(queries[len(queries)-1], "run=run-1234567890") || !strings.Contains(d.table.GetTitle(), "run=run-1234") {
		t.Errorf("run filter: query %q, title %q", queries[len(queries)-1], d.table.GetTitle())
	}
	d.SetConnectorFilter("c1")
	if q := queries[len(queries)-1]; !strings.Contains(q, "connector=c1") || strings.Contains(q, "run=") {
		t.Errorf("connector filter: query %q", q)
	}
	if !d.FilterActive() {
		t.Error("FilterActive() = false with a filter set")
	}
	d.ClearFilter()
	if q := queries[len(queries)-1]; strings.Contains(q, "connector=") || strings.Contains(q, "run=") || d.FilterActive() {
		t.Errorf("cleared filter still asks %q", q)
	}
}

// A full page says it is capped instead of passing for the whole queue (#1111).
func TestDLQ_aFullPageSaysItIsCapped(t *testing.T) {
	d := NewDLQ(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil)
	d.renderList(make([]types.DeadLetter, dlqPageCap), nil)
	if !strings.Contains(d.table.GetTitle(), "first 200") {
		t.Errorf("title %q does not say the list is capped", d.table.GetTitle())
	}
}

// ^R names the run that carried the replay (#1127): it offers the letter's connector's latest run,
// Enter takes it, anything else is the run typed; "-" is no longer "none" (#1377), just a run id the
// hub will not find.
func TestDLQ_replayNamesTheRun(t *testing.T) {
	for _, c := range []struct{ answer, want string }{
		{"", "newRunId=run-latest"},
		{"-", "newRunId=-"},
		{"run-typed", "newRunId=run-typed"},
	} {
		var replayed string
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch {
			case strings.HasSuffix(r.URL.Path, "/replayed"):
				replayed = r.URL.RawQuery
			case strings.Contains(r.URL.Path, "/runs"):
				_, _ = w.Write([]byte(`[{"id":"run-old","startedAt":"2026-10-15T10:00:00Z"},{"id":"run-latest","startedAt":"2026-10-16T10:00:00Z"}]`))
				return
			}
			_, _ = w.Write([]byte(`[]`))
		}))
		var label string
		d := NewDLQ(nil, client.New(config.Context{Server: srv.URL}), nil)
		d.SetAsk(func(l string, _ bool, done func(string)) { label = l; done(c.answer) })
		d.renderList([]types.DeadLetter{{ID: "dl1", ConnectorID: "c1"}}, nil)
		d.table.Select(1, 0)
		d.handleKey(tcell.NewEventKey(tcell.KeyCtrlR, 0, tcell.ModNone))
		srv.Close()
		if !strings.Contains(label, "run-late") {
			t.Errorf("the prompt %q does not offer the latest run", label)
		}
		if replayed != c.want {
			t.Errorf("answer %q: replayed with %q, want %q", c.answer, replayed, c.want)
		}
	}
}

// #1377: no run offered and none typed — ^R marks nothing and says to discard instead.
func TestDLQ_replayWithoutARunIsNotSent(t *testing.T) {
	var replayed bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		replayed = replayed || strings.HasSuffix(r.URL.Path, "/replayed")
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	var said string
	d := NewDLQ(nil, client.New(config.Context{Server: srv.URL}), func(msg string) { said = msg })
	d.SetAsk(func(_ string, _ bool, done func(string)) { done("") })
	d.renderList([]types.DeadLetter{{ID: "dl1", ConnectorID: "c1"}}, nil)
	d.table.Select(1, 0)
	d.handleKey(tcell.NewEventKey(tcell.KeyCtrlR, 0, tcell.ModNone))
	if replayed {
		t.Error("a replay with no run reached the hub")
	}
	if !strings.Contains(said, "^D discards it") {
		t.Errorf("said %q, want the pointer to ^D", said)
	}
}
