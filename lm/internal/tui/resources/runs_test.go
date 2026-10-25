package resources

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

func TestStartedLabel(t *testing.T) {
	// A fixed "now" and a fixed zone so the test is deterministic.
	loc := time.FixedZone("CET", 2*3600)
	now := time.Date(2026, 7, 7, 14, 30, 0, 0, loc)

	cases := []struct {
		name string
		utc  time.Time
		want string
	}{
		{"today shows local time only", time.Date(2026, 7, 7, 12, 5, 9, 0, time.UTC), "14:05:09"},
		{"older shows date + local time", time.Date(2026, 7, 5, 8, 0, 0, 0, time.UTC), "07-05 10:00"},
	}
	for _, c := range cases {
		if got := startedLabel(c.utc, now); got != c.want {
			t.Errorf("%s: startedLabel = %q, want %q", c.name, got, c.want)
		}
	}
}

// The describe panel's started/finished lines render local, second precision — output.
// HumanTimeSeconds, not the raw RFC3339 the hub sends. Expected value computed, not
// hardcoded, so this does not depend on the machine running it being on any particular
// timezone. The table's STARTED column already went through startedLabel (tested above);
// this pins the describe panel, which used to disagree with it in the same file.
func TestRunsDescribe_startedAndFinishedAreLocal(t *testing.T) {
	started, err := time.Parse(time.RFC3339, "2026-09-03T13:43:24Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}
	ended := started.Add(31 * time.Second)
	wantStarted := "started:         " + started.In(time.Local).Format("2006-01-02 15:04:05")
	wantFinished := "finished:        " + ended.In(time.Local).Format("2006-01-02 15:04:05")

	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{ID: "r1", StartedAt: &started, EndedAt: &ended}}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if !strings.Contains(body, wantStarted) {
		t.Errorf("describe body missing %q:\n%s", wantStarted, body)
	}
	if !strings.Contains(body, wantFinished) {
		t.Errorf("describe body missing %q:\n%s", wantFinished, body)
	}
	if strings.Contains(body, "2026-09-03T13:43:24Z") {
		t.Errorf("describe body should render local-formatted, not the raw ISO string:\n%s", body)
	}
}

// The RECORDS column becomes the full in/out/skip triple — SKIPPED joins the CLI
// table here too, in the TUI's same compact idiom.
// setHeader labels the columns from runSortColumns (plus the two un-sortable tails) while render
// writes cells by the col* constants: two ladders that must stay in step. TestRunRow_width... on
// the CLI side pins the COUNT of columns, which a permutation survives — this pins the
// correspondence, so a cell filed under the wrong header goes red instead of shipping.
func TestRunsRender_everyCellSitsUnderItsOwnHeader(t *testing.T) {
	started := time.Date(2026, 9, 8, 1, 20, 11, 0, time.UTC)
	ended := started.Add(time.Second)
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{
		ID: "8953b0c8", ConnectorName: "demo-erp", EndpointName: "warehouses",
		Status: "Completed", StartedAt: &started, EndedAt: &ended,
		RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3, TriggeredBy: "MANUAL",
	}}
	r.render()

	for _, c := range []struct {
		col          int
		header, cell string
	}{
		{colConnector, "CONNECTOR", "demo-erp"},
		{colEndpoint, "ENDPOINT", "warehouses"},
		{colStatus, "STATUS", "Completed"},
		{colStarted, "STARTED", startedLabel(started, time.Now())},
		{colDuration, "DURATION", "1s"},
		{colRecords, "RECORDS", "10/7/3"},
		{colTrigger, "TRIGGER", "MANUAL"},
	} {
		if got := r.table.GetCell(0, c.col).Text; !strings.Contains(got, c.header) {
			t.Errorf("header of column %d = %q, want %q", c.col, got, c.header)
		}
		if got := r.table.GetCell(1, c.col).Text; !strings.Contains(got, c.cell) {
			t.Errorf("cell under %s = %q, want it to contain %q", c.header, got, c.cell)
		}
	}
}

// The :runs table has the same defect the CLI table had: a connector writes one run row per
// endpoint, so a two-table jdbc connector shows two rows that differ in nothing a human reads —
// same connector, same status, same second. The ENDPOINT column is what tells them apart.
func TestRunsRender_endpointColumnTellsSiblingRowsApart(t *testing.T) {
	started := time.Date(2026, 9, 8, 1, 22, 0, 0, time.UTC)
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{
		{ID: "41b06fa9", ConnectorName: "demo-erp", EndpointName: "warehouses", StartedAt: &started},
		{ID: "18c3897f", ConnectorName: "demo-erp", EndpointName: "price_list", StartedAt: &started},
	}
	r.render()

	first, second := r.table.GetCell(1, colEndpoint).Text, r.table.GetCell(2, colEndpoint).Text
	if first != "warehouses" || second != "price_list" {
		t.Errorf("ENDPOINT cells = %q, %q; want warehouses, price_list", first, second)
	}
	if r.table.GetCell(1, colConnector).Text != r.table.GetCell(2, colConnector).Text {
		t.Fatal("test setup: both rows must belong to the same connector")
	}
}

// A run with no endpoint (a row predating endpoints, or one whose endpoint was hard-deleted)
// leaves the cell empty rather than showing a placeholder that would read as a name.
func TestRunsRender_runWithoutEndpointLeavesTheCellEmpty(t *testing.T) {
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{ID: "r1", ConnectorName: "demo-erp"}}
	r.render()
	if got := r.table.GetCell(1, colEndpoint).Text; got != "" {
		t.Errorf("ENDPOINT cell for a run with no endpoint = %q, want empty", got)
	}
}

// The describe panel names the endpoint too — it is where an operator lands after 'd', and the
// counters below it are meaningless without knowing which table produced them.
func TestRunsDescribe_namesTheEndpoint(t *testing.T) {
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{ID: "r1", ConnectorName: "demo-erp", EndpointName: "warehouses"}}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if !strings.Contains(body, "endpoint:        warehouses") {
		t.Errorf("describe body should name the endpoint:\n%s", body)
	}
}

// d on a failed run must say WHY first: written last, after a dozen counters, the reason sat below
// the fold of a 40% panel (#1173). And connector: names it as the list does — the id is on id:.
func TestRunsDescribe_aFailedRunSaysWhyRightAfterItsStatus(t *testing.T) {
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{ID: "r1", ConnectorID: "9f1c2d3e-0000-4000-8000-000000000001", ConnectorName: "acme-kafka",
		Status: "Failed", ErrorMessage: "the broker did not answer\nwithin the metadata timeout", ChunkCount: 3}}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	lines := strings.Split(body, "\n")
	status := slices.IndexFunc(lines, func(l string) bool { return strings.HasPrefix(l, "status:") })
	errAt := slices.IndexFunc(lines, func(l string) bool { return strings.HasPrefix(l, "error:") })
	if status < 0 || errAt != status+1 {
		t.Errorf("error: is line %d, status: line %d — the reason must follow the status:\n%s", errAt, status, body)
	}
	if !strings.Contains(body, "within the metadata timeout") {
		t.Errorf("the whole multi-line error must be shown:\n%s", body)
	}
	if !strings.Contains(body, "connector:       acme-kafka\n") {
		t.Errorf("connector: must name the connector, not its id:\n%s", body)
	}
}

func TestRunsRender_recordsCellShowsInOutSkip(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{
		{ID: "r1", RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3},
	}
	r.render()
	if got := r.table.GetCell(1, colRecords).Text; got != "10/7/3" {
		t.Errorf("RECORDS cell = %q, want %q", got, "10/7/3")
	}
}

// The describe panel adds records_skipped right after records_out — the full
// conservation triple must be readable there too.
func TestRunsDescribe_includesRecordsSkippedAfterRecordsOut(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{
		{ID: "r1", RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3},
	}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	outIdx := strings.Index(body, "records_out:")
	skipIdx := strings.Index(body, "records_skipped:")
	if outIdx == -1 || skipIdx == -1 || skipIdx < outIdx {
		t.Fatalf("expected records_skipped right after records_out, got body:\n%s", body)
	}
	if !strings.Contains(body, "records_skipped: 3\n") {
		t.Errorf("records_skipped value missing/wrong, got body:\n%s", body)
	}
}

// The describe panel adds cell_errors right after records_skipped — a cell hook nulling a
// value it cannot parse is invisible everywhere else, so this is the one door that shows it.
func TestRunsDescribe_includesCellErrorsAfterRecordsSkipped(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{
		{ID: "r1", RecordsIn: 10, RecordsOut: 7, RecordsSkipped: 3, CellErrors: 2},
	}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	skipIdx := strings.Index(body, "records_skipped:")
	cellIdx := strings.Index(body, "cell_errors:")
	if skipIdx == -1 || cellIdx == -1 || cellIdx < skipIdx {
		t.Fatalf("expected cell_errors right after records_skipped, got body:\n%s", body)
	}
	if !strings.Contains(body, "cell_errors:     2\n") {
		t.Errorf("cell_errors value/padding missing/wrong, got body:\n%s", body)
	}
}

// #1430: the panel names each version of a Kotlin script the run ran, as `lm run get` does.
func TestRunsDescribe_namesTheScriptVersions(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{
		{ID: "r1", RecordsIn: 3, RecordsOut: 3, Scripts: []types.RunScript{{Name: "hooks:shipping-fee.kts", SHA256: "c1c59e5c"}}},
	}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if !strings.Contains(body, "script:          hooks:shipping-fee.kts  sha256 c1c59e5c\n") {
		t.Errorf("script line missing, got body:\n%s", body)
	}
}

// The :runs page carried the same silence the CLI half of this change removed: it fetched the
// newest 200 runs of the WHOLE hub and sieved them in renderList, so a connector whose runs had
// scrolled out of that page showed an EMPTY table under a ":runs [conn=…]" title — the drill-down
// answering "this connector never ran" about one that had. The filter is part of the question now.
func TestRunsSetFilter_narrowsAtTheHubNotInTheRenderer(t *testing.T) {
	var asked []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query().Get("connector")
		asked = append(asked, q)
		if q == "c-1" {
			_, _ = w.Write([]byte(`[{"id":"41b06fa9","connectorId":"c-1","connectorName":"demo-erp",` +
				`"endpointName":"warehouses","status":"Completed"}]`))
			return
		}
		// A busy hub's global page: newer runs of other connectors, filling the window.
		_, _ = w.Write([]byte(`[{"id":"99999999","connectorId":"c-9","connectorName":"noisy-neighbour",` +
			`"status":"Completed"}]`))
	}))
	defer srv.Close()

	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns),
		client: client.New(config.Context{Server: srv.URL})}
	r.SetFilter("c-1", "")

	if len(asked) == 0 || asked[len(asked)-1] != "c-1" {
		t.Errorf("the filter must ride the query, connector params seen: %v", asked)
	}
	if got := r.table.GetCell(1, colEndpoint).Text; got != "warehouses" {
		t.Errorf("the filtered run must be listed, ENDPOINT cell = %q", got)
	}
	if got := r.table.GetCell(1, colConnector).Text; got == "noisy-neighbour" {
		t.Errorf("another connector's run must not reach the filtered page")
	}
}

// Clearing the filter asks the ledger again, unfiltered — the drill-down is a question, not a view
// over something already fetched.
func TestRunsClearFilter_asksTheLedgerAgain(t *testing.T) {
	var asked []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.Query().Get("connector"))
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns),
		client: client.New(config.Context{Server: srv.URL})}
	r.SetFilter("c-1", "")
	r.ClearFilter()

	if len(asked) < 2 || asked[len(asked)-1] != "" {
		t.Errorf("clearing must re-ask without a connector param, saw: %v", asked)
	}
	if r.FilterActive() {
		t.Error("FilterActive must be false after ClearFilter")
	}
}

// The endpoint drill-down (Enter on :endpoints) had nothing to ask with: it handed an ENDPOINT id
// to a filter that compared it against each run's CONNECTOR id, which no run can satisfy — so the
// page its own reference calls "that endpoint's own runs" was always empty. It asks by endpoint.
func TestRunsSetEndpointFilter_asksTheHubByEndpoint(t *testing.T) {
	var connectorParam, endpointParam []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		connectorParam = append(connectorParam, r.URL.Query().Get("connector"))
		endpointParam = append(endpointParam, r.URL.Query().Get("endpoint"))
		if r.URL.Query().Get("endpoint") == "e-7" {
			_, _ = w.Write([]byte(`[{"id":"41b06fa9","connectorName":"demo-erp",` +
				`"endpointName":"warehouses","status":"Completed"}]`))
			return
		}
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns),
		client: client.New(config.Context{Server: srv.URL})}
	r.SetEndpointFilter("e-7", "")

	if len(endpointParam) == 0 || endpointParam[len(endpointParam)-1] != "e-7" {
		t.Errorf("the endpoint must ride the query, saw endpoint params %v", endpointParam)
	}
	if last := connectorParam[len(connectorParam)-1]; last != "" {
		t.Errorf("an endpoint id must not be sent as a connector filter, got %q", last)
	}
	if got := r.table.GetCell(1, colEndpoint).Text; got != "warehouses" {
		t.Errorf("the endpoint's run must be listed, ENDPOINT cell = %q", got)
	}
}

// The two drill-downs are two questions, not two halves of one: arriving from a connector after an
// endpoint (or the reverse) must not keep asking the previous one.
func TestRunsFilters_eachDrillDownReplacesTheOther(t *testing.T) {
	var queries []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		queries = append(queries, r.URL.Query().Get("connector")+"/"+r.URL.Query().Get("endpoint"))
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns),
		client: client.New(config.Context{Server: srv.URL})}
	r.SetFilter("c-1", "")
	r.SetEndpointFilter("e-7", "")
	r.SetFilter("c-2", "")
	r.ClearFilter()

	want := []string{"c-1/", "/e-7", "c-2/", "/"}
	if len(queries) != len(want) {
		t.Fatalf("expected one fetch per drill-down, got %v", queries)
	}
	for i, w := range want {
		if queries[i] != w {
			t.Errorf("fetch %d asked %q, want %q (full sequence: %v)", i, queries[i], w, queries)
		}
	}
	if r.FilterActive() {
		t.Error("FilterActive must be false once both filters are cleared")
	}
}

func TestRuns_aQueuedRunSortsFirstAndRendersWithoutAStart(t *testing.T) {
	started := time.Date(2026, 9, 3, 13, 43, 24, 0, time.UTC)
	r := &Runs{sort: style.NewColumnSort(runSortColumns), items: []types.Run{
		{ID: "old", Status: "Completed", StartedAt: &started},
		{ID: "queued", Status: "Queued"},
	}}
	r.applySort()
	if r.items[0].ID != "queued" {
		t.Fatalf("newest-first default must put the queued run on top, got %s first", r.items[0].ID)
	}
	if got := durationOf(r.items[0]); got != 0 {
		t.Errorf("durationOf(queued) = %v, want 0", got)
	}
	if got := startedCellLabel(r.items[0], time.Now()); got != "queued" {
		t.Errorf("STARTED label for a queued run = %q, want %q", got, "queued")
	}
}

// cancelToast drives Runs.cancel against a hub that answers the cancel with the given status word.
func cancelToast(t *testing.T, word string) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusAccepted)
		_, _ = fmt.Fprintf(w, `{"status":%q,"runId":"x"}`, word)
	}))
	t.Cleanup(srv.Close)
	var toasted string
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns),
		client: client.New(config.Context{Server: srv.URL}),
		toast:  func(m string) { toasted = m }}
	r.items = []types.Run{{ID: "11111111-0000-4000-8000-000000000000", ConnectorName: "demo-erp", Status: "Queued"}}
	r.render()
	r.table.Select(1, 0)
	r.cancel()
	return toasted
}

func TestRuns_cancelOfAQueuedRunToastsThatItHadNotStarted(t *testing.T) {
	got := cancelToast(t, "cancelled")
	if !strings.Contains(got, "it had not started") || strings.Contains(got, "current chunk") {
		t.Errorf("toast = %q", got)
	}
}

func TestRuns_cancelOfARunningRunToastsTheChunkBoundary(t *testing.T) {
	got := cancelToast(t, "cancellation requested")
	if !strings.Contains(got, "ends after the current chunk") {
		t.Errorf("toast = %q", got)
	}
}

func TestRuns_describeOfAQueuedRunSaysQueuedNotBlank(t *testing.T) {
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{ID: "r1", Status: "Queued"}}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok || !strings.Contains(body, "started:         queued") {
		t.Errorf("describe body = %q", body)
	}
}

// The run panel says how many rows the run quarantined, as lm run get does (#1111).
func TestRunsDescribe_showsDeadLetters(t *testing.T) {
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.renderList([]types.Run{{ID: "r1", DeadLetterCount: 3}}, nil)
	r.table.Select(1, 0)
	_, body, _ := r.SelectedDescribe()
	if !strings.Contains(body, "dead_letters:    3") {
		t.Errorf("describe misses the dead-letter count:\n%s", body)
	}
}

// A drill-down's title names what it is narrowed to — the connector or endpoint the user pressed
// Enter on, by NAME, not by an 8-character id (#1175); an id with no name still shows short. And
// :endpoints opened from the palette, with no connector, says how to fill it.
func TestDrillDownTitles_nameWhatTheyAreNarrowedTo(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	c := client.New(config.Context{Server: srv.URL})

	e := NewEndpoints(nil, c, nil, nil)
	e.Refresh()
	if got := e.table.GetCell(1, 0).Text; !strings.Contains(got, "Enter on a :sources row") {
		t.Errorf("empty :endpoints says %q", got)
	}
	e.SetFilter("2d80d457-1111", "erp-orders")
	if got := e.table.GetTitle(); got != " :endpoints › erp-orders " {
		t.Errorf(":endpoints title = %q", got)
	}
	e.SetFilter("2d80d457-1111", "")
	if got := e.table.GetTitle(); got != " :endpoints › 2d80d457 " {
		t.Errorf(":endpoints title without a name = %q", got)
	}

	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns), client: c}
	r.SetFilter("2d80d457-1111", "erp-orders")
	if got := r.table.GetTitle(); got != " :runs › connector erp-orders " {
		t.Errorf(":runs connector title = %q", got)
	}
	r.SetEndpointFilter("edad9649-2222", "warehouses")
	if got := r.table.GetTitle(); got != " :runs › endpoint warehouses " {
		t.Errorf(":runs endpoint title = %q", got)
	}
}

// #1351: EVALUATED appears only when the hub answered for some run; the live ticker re-renders it,
// which is the TUI's way of waiting for kind:Process.
func TestRunsRender_evaluatedColumnOnlyWhenTheHubAnswered(t *testing.T) {
	r := &Runs{table: tview.NewTable(), sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{ID: "r1", ConnectorName: "demo-erp"}}
	r.render()
	if got := r.table.GetCell(0, colEvaluated).Text; got != "" {
		t.Fatalf("no run carries evaluated, yet the header reads %q", got)
	}

	yes, no := true, false
	r.items = []types.Run{{ID: "r1", Evaluated: &yes}, {ID: "r2", Evaluated: &no}, {ID: "r3"}}
	r.render()
	if got := r.table.GetCell(0, colEvaluated).Text; got != "EVALUATED" {
		t.Fatalf("header = %q, want EVALUATED", got)
	}
	for i, want := range []string{"yes", "no", "—"} {
		if got := r.table.GetCell(i+1, colEvaluated).Text; got != want {
			t.Errorf("row %d EVALUATED = %q, want %q", i+1, got, want)
		}
	}
}
