package resources

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/tui/newkind"
	"github.com/icreated/lumnik-open/lm/internal/tui/style"
	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

// Server- and user-derived text is DATA, not markup: tview table cells always
// parse color tags, so a value containing "[red]" must render literally.
const evil = "[red]evil"

func TestDataCellEscapes(t *testing.T) {
	if got := dataCell(evil).Text; got != tview.Escape(evil) {
		t.Errorf("dataCell must escape, got %q", got)
	}
}

func TestViewListAndRecentsCellsEscape(t *testing.T) {
	v := &ViewPage{list: tview.NewTable(), recentsUI: tview.NewTable(),
		recents: []string{evil + ".yaml"}}
	v.fill(&client.ViewResult{Title: "T",
		Columns: []client.ViewColumn{{Name: "c", Label: evil}},
		Rows:    [][]string{{evil}}})
	if got := v.list.GetCell(1, 0).Text; strings.Contains(got, evil) {
		t.Errorf("view list cell unescaped: %q", got)
	}
	if got := v.list.GetCell(0, 0).Text; strings.Contains(got, strings.ToUpper(evil)) {
		t.Errorf("view column label unescaped (labels are server-derived): %q", got)
	}
	v.renderRecents()
	if got := v.recentsUI.GetCell(0, 0).Text; strings.Contains(got, evil) {
		t.Errorf("recents cell unescaped: %q", got)
	}
}

// The :inbox MESSAGE is user-templated ({col} from CSV data) — the hottest
// cell in the house. The literal " [traité]" marker must keep rendering.
func TestInboxMessageCellEscapes(t *testing.T) {
	i := &Inbox{table: tview.NewTable(), items: []client.InboxEntry{
		{ID: 1, Process: evil, Message: evil, EntityKey: evil, Acked: true}}}
	i.render()
	msg := i.table.GetCell(1, 3).Text
	if strings.Contains(msg, evil) {
		t.Errorf("inbox MESSAGE unescaped: %q", msg)
	}
	if !strings.Contains(msg, "traité") {
		t.Errorf("the [traité] marker must survive: %q", msg)
	}
	if proc := i.table.GetCell(1, 2).Text; strings.Contains(proc, evil) {
		t.Errorf("inbox PROCESS unescaped: %q", proc)
	}
}

func TestDlqCellsEscape(t *testing.T) {
	d := &DLQ{table: tview.NewTable(),
		sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"})}
	d.items = append(d.items, types.DeadLetter{
		ID: "1", ConnectorID: "c", RunID: "r",
		SkipReason: evil, ErrorMessage: evil, Attempts: 1})
	d.render()
	if got := d.table.GetCell(1, 3).Text; strings.Contains(got, evil) {
		t.Errorf("dlq SKIP_REASON unescaped: %q", got)
	}
	if got := d.table.GetCell(1, 5).Text; strings.Contains(got, evil) {
		t.Errorf("dlq ERROR unescaped: %q", got)
	}
}

func TestAskRenderEscapesServerData(t *testing.T) {
	a := &Ask{app: tview.NewApplication(),
		headline: tview.NewTextView().SetDynamicColors(true),
		sqlView:  tview.NewTextView().SetDynamicColors(true),
		answer:   tview.NewTextView().SetDynamicColors(true),
		table:    tview.NewTable(), input: tview.NewInputField()}
	a.render(&client.AskResult{Answer: evil, SQL: evil, RowCount: 1,
		Columns: []string{evil}, Rows: [][]string{{evil}}})
	if got := a.sqlView.GetText(false); strings.Contains(got, evil) {
		t.Errorf("SQL view carries raw server SQL unescaped: %q", got)
	}
	if got := a.answer.GetText(false); strings.Contains(got, evil) {
		t.Errorf("answer unescaped: %q", got)
	}
	if got := a.table.GetCell(1, 0).Text; strings.Contains(got, evil) {
		t.Errorf("ask result cell unescaped: %q", got)
	}
	if got := a.table.GetCell(0, 0).Text; strings.Contains(got, evil) {
		t.Errorf("ask column header unescaped: %q", got)
	}
	a.renderNotice(evil)
	if got := a.answer.GetText(false); strings.Contains(got, evil) {
		t.Errorf("notice unescaped: %q", got)
	}
}

func TestRunsStatusCellEscapes(t *testing.T) {
	c := statusCell(evil, statusFor("Completed"))
	if strings.Contains(c.Text, evil) {
		t.Errorf("runs status cell unescaped: %q", c.Text)
	}
}

// The Refresh() error rows concatenate a literal with raw err.Error() —
// the inventory blind spot Copilot caught twice (round 2, PR #135).
func TestScopesRefreshErrorRowEscapes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
		_, _ = w.Write([]byte(evil))
	}))
	t.Cleanup(srv.Close)
	s := &Scopes{table: tview.NewTable(),
		client: client.New(config.Context{Server: srv.URL, Token: "static"})}
	s.Refresh()
	if got := s.table.GetCell(0, 0).Text; strings.Contains(got, evil) {
		t.Errorf("scopes error row unescaped: %q", got)
	}
}

// The :new issues screen (rung 2) renders raw server error text into a
// DynamicColors TextView — same class rung 5 already escapes.
func TestNewKindIssuesEscapeServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
		_, _ = w.Write([]byte(evil))
	}))
	t.Cleanup(srv.Close)
	p := &NewKind{kinds: newkind.All(),
		client:  client.New(config.Context{Server: srv.URL, Token: "static"}),
		issues:  tview.NewTextView().SetDynamicColors(true),
		pages:   tview.NewPages(),
		suspend: func(f func()) { f() },
		editFn: func(hint, content string) (string, bool, error) {
			return "kind: Connector\nname: x\n", true, nil
		}}
	p.edit(newkind.KindTemplate{Kind: "Connector", Template: "kind: Connector\n"}, "kind: Connector\n")
	text := p.issues.GetText(false)
	if text == "" {
		t.Fatal("the server error must reach the issues view")
	}
	if strings.Contains(text, evil) {
		t.Errorf("issues view carries raw server text unescaped: %q", text)
	}
}

// Conflict VALUES are raw ERP data — the rawest strings this TUI displays.
func TestConflictsCellsAndDescribeEscape(t *testing.T) {
	c := &Conflicts{table: tview.NewTable(), sortCol: style.NewColumnSort([]string{"ENTITY", "KEY"})}
	c.items = []client.Conflict{{Entity: evil, Key: evil, Columns: []string{evil},
		Conflicts: map[string][]client.DetailSource{evil: {{Src: evil, Value: evil}}}}}
	c.render()
	for col := 0; col < 4; col++ {
		if got := c.table.GetCell(1, col).Text; strings.Contains(got, evil) {
			t.Errorf("conflicts cell %d unescaped: %q", col, got)
		}
	}
	c.table.Select(1, 0)
	// The title is escaped by DescribePanel.SetContent (see tui/describe_test.go), so the
	// raw title returned here is correct — only the body is this page's responsibility.
	_, body, ok := c.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("conflicts describe body unescaped: %q", body)
	}
}

// Workflow doc keys and state values are raw ERP data.
func TestObservationsCellsEscape(t *testing.T) {
	o := &Observations{table: tview.NewTable(), items: []client.Observation{
		{Workflow: evil, DocKey: evil, State: evil, Since: time.Now()}}}
	o.render()
	for col := 0; col < 3; col++ {
		if got := o.table.GetCell(1, col).Text; strings.Contains(got, evil) {
			t.Errorf("observations cell %d unescaped: %q", col, got)
		}
	}
}

// The describe panel's title and body carry the same raw Workflow/DocKey/State —
// a page is not covered until its case is here.
func TestObservationsDescribeEscape(t *testing.T) {
	o := &Observations{table: tview.NewTable(), items: []client.Observation{
		{Workflow: evil, DocKey: evil, State: evil, Since: time.Now()}}}
	o.table.Select(1, 0)
	// The title is escaped by DescribePanel.SetContent (see tui/describe_test.go), so the
	// raw title returned here is correct — only the body is this page's responsibility.
	_, body, ok := o.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("observations describe body unescaped: %q", body)
	}
}

// The describe BODY is each page's responsibility (the title is the sink's — see
// tui/describe_test.go). These five carry the rawest strings in the app: an ingested
// record, a dead-letter payload, an ingestion error, a process-emitted message, and a
// whole connector config.
func TestViewPageDescribeBodyEscapes(t *testing.T) {
	v := &ViewPage{list: tview.NewTable(), recentsUI: tview.NewTable()}
	v.fill(&client.ViewResult{Title: "T",
		Columns: []client.ViewColumn{{Name: "c", Label: evil}},
		Rows:    [][]string{{evil}}})
	v.list.Select(1, 0)
	_, body, ok := v.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("view describe body unescaped: %q", body)
	}
}

func TestDlqDescribeBodyEscapes(t *testing.T) {
	d := &DLQ{table: tview.NewTable(),
		sortCol: style.NewColumnSort([]string{"CONNECTOR", "CAPTURED", "REASON"})}
	d.items = []types.DeadLetter{{ID: "1", ConnectorID: evil, RunID: "r",
		SkipReason: evil, ErrorMessage: evil, Payload: evil}}
	d.render()
	d.table.Select(1, 0)
	_, body, ok := d.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("dlq describe body unescaped: %q", body)
	}
}

func TestRunsDescribeBodyEscapes(t *testing.T) {
	r := &Runs{table: tview.NewTable(),
		sort: style.NewColumnSort(runSortColumns)}
	r.items = []types.Run{{ID: "1", ConnectorID: evil, Status: evil,
		TriggeredBy: evil, ErrorMessage: evil}}
	r.render()
	r.table.Select(1, 0)
	_, body, ok := r.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("runs describe body unescaped: %q", body)
	}
}

func TestInboxDescribeBodyEscapes(t *testing.T) {
	i := &Inbox{table: tview.NewTable(), items: []client.InboxEntry{
		{ID: 1, OccurredAt: evil, Type: evil, Process: evil,
			Message: evil, Entity: evil, EntityKey: evil}}}
	i.render()
	i.table.Select(1, 0)
	_, body, ok := i.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("inbox describe body unescaped: %q", body)
	}
}

func TestSourcesDescribeBodyEscapes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// evil is "[red]evil" — no JSON-special characters, so plain concatenation is safe.
		_, _ = w.Write([]byte(`{"id":"c-1","tenantId":1,"name":"` + evil + `","typeId":"` + evil +
			`","schedule":"` + evil + `","enabled":true,"status":"` + evil +
			`","config":{"url":"` + evil + `"}}`))
	}))
	t.Cleanup(srv.Close)

	c := &Sources{table: tview.NewTable(),
		client:  client.New(config.Context{Server: srv.URL, Token: "static"}),
		sortCol: style.NewColumnSort([]string{"NAME", "TYPE", "SCHED", "ENABLED"})}
	c.items = []types.Connector{{ID: "c-1", Name: "probe"}}
	c.render()
	c.table.Select(1, 0)
	_, body, ok := c.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("sources describe body unescaped: %q", body)
	}
}

func TestEndpointsDescribeBodyEscapes(t *testing.T) {
	p := &Endpoints{table: tview.NewTable(), endpoints: []types.Endpoint{
		{ID: "1", ConnectorID: evil, Name: evil, Method: evil,
			Path: evil, Schedule: evil, LastRunStatus: evil}}}
	p.render()
	p.table.Select(1, 0)
	_, body, ok := p.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("endpoints describe body unescaped: %q", body)
	}
}

func TestProcessesDescribeBodyEscapes(t *testing.T) {
	p := &Processes{table: tview.NewTable(), items: []client.ProcessInfo{
		{Name: evil, Table: evil, Emit: evil}}}
	p.render()
	p.table.Select(1, 0)
	_, body, ok := p.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("processes describe body unescaped: %q", body)
	}
}

func TestScopesDescribeBodyEscapes(t *testing.T) {
	s := &Scopes{table: tview.NewTable(), items: []client.RagScope{
		{Scope: evil, LastIndexed: evil}}}
	s.render()
	s.table.Select(1, 0)
	_, body, ok := s.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("scopes describe body unescaped: %q", body)
	}
}

// Integration names, event types and third-party error bodies are external data.
func TestOutputsCellsAndDescribeEscape(t *testing.T) {
	o := &Outputs{table: tview.NewTable(), items: []client.Output{
		{ID: 1, IntegrationID: 2, Integration: evil, EventType: evil, Error: evil,
			AttemptedAt: time.Now()}}}
	o.render()
	for col := 0; col < 5; col++ {
		if got := o.table.GetCell(1, col).Text; strings.Contains(got, evil) {
			t.Errorf("outputs cell %d unescaped: %q", col, got)
		}
	}
	o.table.Select(1, 0)
	_, body, ok := o.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if strings.Contains(body, evil) {
		t.Errorf("outputs describe body unescaped: %q", body)
	}
}

// This inventory is hand-rolled, so it is only as complete as its last author. Scan the
// package for SelectedDescribe implementations and fail when one has no entry here —
// the twelfth page must not be able to arrive silently. (The three fields' exact names
// per page are in the audit; what this test guards is COVERAGE, not correctness.)
func TestEveryDescribePageIsInTheEscapeInventory(t *testing.T) {
	covered := map[string]bool{
		"Conflicts": true, "DLQ": true, "Endpoints": true, "Inbox": true,
		"Observations": true, "Outputs": true, "Processes": true, "Runs": true, "Scopes": true,
		"Sources": true, "ViewPage": true,
	}
	// Exempt, with the reason: NewKind's body is compile-time embedded template text and
	// highlightYAML already escapes it — there is no runtime data on that path.
	exempt := map[string]bool{"NewKind": true}

	// Directory-scoped by design: filepath.Glob("*.go") only sees this package. Every
	// SelectedDescribe implementation lives here today; a future one added in a sibling
	// package would compile and run fine while evading this scan entirely.
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatalf("glob: %v", err)
	}
	re := regexp.MustCompile(`func \(\w+ \*(\w+)\) SelectedDescribe\(`)
	found := 0
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		src, err := os.ReadFile(f)
		if err != nil {
			t.Fatalf("read %s: %v", f, err)
		}
		for _, m := range re.FindAllStringSubmatch(string(src), -1) {
			found++
			page := m[1]
			if !covered[page] && !exempt[page] {
				t.Errorf("%s implements SelectedDescribe but has no case in this file — "+
					"its body reaches a dynamic-colors view, so add its escape test (or an "+
					"exemption with a reason)", page)
			}
		}
	}
	// found counts BOTH covered and exempt implementations, so it must be compared
	// against their sum — comparing against len(covered) alone leaves a full page of
	// slack (today's one exempt page) under which deleting NewKind's SelectedDescribe
	// would go unnoticed: exempt pages have no test calling the method, so nothing else
	// would fail either.
	if found != len(covered)+len(exempt) {
		t.Errorf("found %d SelectedDescribe implementations, inventory lists %d covered + %d exempt = %d — "+
			"either a new page arrived without an inventory entry, or a covered/exempt page was "+
			"deleted without removing its entry", found, len(covered), len(exempt), len(covered)+len(exempt))
	}
}
