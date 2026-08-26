package resources

import (
	"strings"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/rivo/tview"
)

func status502() *int { s := 502; return &s }

func sampleOutputs() []client.Output {
	return []client.Output{
		{ID: 91, IntegrationID: 3, Integration: "n8n-prod", EventType: "decision.dormant.commande",
			HTTPStatus: status502(), Error: "connect timed out", DurationMs: 3001,
			AttemptedAt: time.Now().Add(-2 * time.Hour)},
		{ID: 92, IntegrationID: 3, Integration: "n8n-prod", EventType: "data.row.changed",
			HTTPStatus: nil, Error: "", DurationMs: 12,
			AttemptedAt: time.Now().Add(-5 * time.Minute), Succeeded: true},
	}
}

// The server orders failures first; the page renders that order and never re-derives it.
func TestOutputsRenderKeepsServerOrderAndShowsStatus(t *testing.T) {
	o := &Outputs{table: tview.NewTable(), items: sampleOutputs()}
	o.render()
	if got := o.table.GetCell(1, 0).Text; got != "n8n-prod" {
		t.Errorf("INTEGRATION = %q", got)
	}
	if got := o.table.GetCell(1, 2).Text; !strings.Contains(got, "502") {
		t.Errorf("STATUS = %q, want the HTTP status", got)
	}
	if got := o.table.GetCell(2, 2).Text; !strings.Contains(got, "ok") {
		t.Errorf("a succeeded row's STATUS = %q, want ok", got)
	}
	if got := o.table.GetCell(1, 3).Text; got != "2h" {
		t.Errorf("WHEN = %q, want the humanized age", got)
	}
}

func TestOutputsDescribeShowsTheFailure(t *testing.T) {
	o := &Outputs{table: tview.NewTable(), items: sampleOutputs()}
	o.render()
	o.table.Select(1, 0)
	title, body, ok := o.SelectedDescribe()
	if !ok {
		t.Fatal("SelectedDescribe returned !ok for a selected row")
	}
	if !strings.Contains(title, "n8n-prod") {
		t.Errorf("title = %q", title)
	}
	for _, want := range []string{"decision.dormant.commande", "502", "connect timed out"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe body missing %q:\n%s", want, body)
		}
	}
}

func TestOutputsDescribeEmptyWithoutSelection(t *testing.T) {
	o := &Outputs{table: tview.NewTable()}
	if _, _, ok := o.SelectedDescribe(); ok {
		t.Error("no rows -> describe must report !ok, not panic")
	}
}

// A succeeded row must never reach o.client.OutputReplay — the guard has to refuse before that
// call, or a nil client (as here) panics. A broken guard (e.g. an inverted condition) would
// crash this test instead of just failing an assertion.
func TestOutputsCtrlRRefusesASucceededRowWithoutTouchingTheClient(t *testing.T) {
	var toasted string
	o := &Outputs{
		table: tview.NewTable(),
		items: []client.Output{
			{ID: 92, IntegrationID: 3, Integration: "n8n-prod", EventType: "data.row.changed",
				HTTPStatus: nil, Error: "", DurationMs: 12,
				AttemptedAt: time.Now(), Succeeded: true},
		},
		client: nil,
		toast:  func(msg string) { toasted = msg },
	}
	o.render()
	o.table.Select(1, 0)

	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("^R on a succeeded row must refuse before touching the client, panicked: %v", r)
		}
	}()
	o.handleKey(tcell.NewEventKey(tcell.KeyCtrlR, 0, tcell.ModNone))

	if !strings.Contains(toasted, "nothing to replay") {
		t.Errorf("toast = %q, want the refusal message", toasted)
	}
}
