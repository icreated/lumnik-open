package types

import (
	"encoding/json"
	"testing"
)

// A hub older than this change never sends recordsSkipped — lm and the hub ship
// together, but the field must decode safely (as 0) rather than erroring, so an
// old hub against a new lm degrades instead of breaking.
func TestRun_decodesMissingRecordsSkippedAsZero(t *testing.T) {
	body := `{"id":"r1","connectorId":"c1","status":"Completed","recordsIn":10,"recordsOut":10}`
	var r Run
	if err := json.Unmarshal([]byte(body), &r); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if r.RecordsSkipped != 0 {
		t.Errorf("RecordsSkipped = %d, want 0 for a payload omitting the field", r.RecordsSkipped)
	}
}

func TestRun_decodesRecordsSkipped(t *testing.T) {
	body := `{"id":"r1","connectorId":"c1","status":"Completed","recordsIn":10,"recordsOut":7,"recordsSkipped":3}`
	var r Run
	if err := json.Unmarshal([]byte(body), &r); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if r.RecordsSkipped != 3 {
		t.Errorf("RecordsSkipped = %d, want 3", r.RecordsSkipped)
	}
}

// A hub older than this change never sends cellErrors — same degrade-not-break contract
// as recordsSkipped above: an old hub against a new lm must decode the field as 0.
func TestRun_decodesMissingCellErrorsAsZero(t *testing.T) {
	body := `{"id":"r1","connectorId":"c1","status":"Completed","recordsIn":10,"recordsOut":10}`
	var r Run
	if err := json.Unmarshal([]byte(body), &r); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if r.CellErrors != 0 {
		t.Errorf("CellErrors = %d, want 0 for a payload omitting the field", r.CellErrors)
	}
}

func TestRun_decodesCellErrors(t *testing.T) {
	body := `{"id":"r1","connectorId":"c1","status":"Completed","recordsIn":10,"recordsOut":10,"cellErrors":5}`
	var r Run
	if err := json.Unmarshal([]byte(body), &r); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if r.CellErrors != 5 {
		t.Errorf("CellErrors = %d, want 5", r.CellErrors)
	}
}
