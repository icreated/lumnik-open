package resources

import (
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/types"
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
}

func TestEndpoints_renderRows_empty(t *testing.T) {
	p := &Endpoints{}
	p.endpoints = []types.Endpoint{}
	rows := p.renderRows()
	if len(rows) != 0 {
		t.Fatalf("want 0 rows, got %d", len(rows))
	}
}
