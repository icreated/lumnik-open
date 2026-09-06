package resources

import (
	"strings"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/types"
	"github.com/rivo/tview"
)

func TestEndpoints_renderRows(t *testing.T) {
	now := time.Date(2026, 5, 24, 10, 0, 0, 0, time.UTC)
	p := &Endpoints{}
	// Uppercase statuses are deliberate here: they exercise the case-insensitive status mapping.
	p.endpoints = []types.Endpoint{
		{Name: "charges", Method: "GET", Path: "/v1/charges", Schedule: "*/15 * * * *", LastRunStatus: "COMPLETED", LastRunAt: &now},
		{Name: "customers", Method: "GET", Path: "/v1/customers", Schedule: "0 * * * *", LastRunStatus: "FAILED"},
	}
	rows := p.renderRows()
	if len(rows) != 2 {
		t.Fatalf("want 2 rows, got %d", len(rows))
	}
	if rows[0][0] != "charges" {
		t.Errorf("first row name: %s", rows[0][0])
	}
	if rows[0][1] != "GET" {
		t.Errorf("first row method: %s", rows[0][1])
	}
	if rows[0][2] != "/v1/charges" {
		t.Errorf("first row path: %s", rows[0][2])
	}
	if rows[0][3] != "*/15 * * * *" {
		t.Errorf("first row schedule: %s", rows[0][3])
	}
	if rows[0][5] != "COMPLETED" {
		t.Errorf("first row status: %s", rows[0][5])
	}
	if rows[1][4] != "" {
		t.Errorf("second row last_run should be empty, got: %s", rows[1][4])
	}
	if want := now.In(time.Local).Format("2006-01-02 15:04"); rows[0][4] != want {
		t.Errorf("first row LAST_RUN = %q, want %q (local, minute precision)", rows[0][4], want)
	}
}

// The describe panel's "last_run_at:" line goes to second precision, matching every other
// detail pane (sources, dlq, outputs, observations); the table cell one row up stays at
// minute precision, matching every other table. Expected value computed, not hardcoded.
func TestEndpoints_describeLastRunIsLocalSecondsPrecision(t *testing.T) {
	now := time.Date(2026, 5, 24, 10, 0, 7, 0, time.UTC)
	want := "last_run_at:     " + now.In(time.Local).Format("2006-01-02 15:04:05")

	p := &Endpoints{table: tview.NewTable()}
	p.endpoints = []types.Endpoint{{Name: "charges", LastRunAt: &now}}
	p.table.Select(1, 0) // no render() call needed: selectedItem reads p.endpoints directly
	_, body, ok := p.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if !strings.Contains(body, want) {
		t.Errorf("describe body missing %q:\n%s", want, body)
	}
}

func TestEndpoints_renderRows_empty(t *testing.T) {
	p := &Endpoints{}
	p.endpoints = []types.Endpoint{}
	rows := p.renderRows()
	if len(rows) != 0 {
		t.Fatalf("want 0 rows, got %d", len(rows))
	}
}
