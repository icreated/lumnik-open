package tui

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func TestFormatSummaryJoinsNames(t *testing.T) {
	wm := "updated"
	sel := []client.JdbcTable{
		{Schema: "public", Name: "c_bpartner", SuggestedWatermark: &wm, RowEstimate: 10},
		{Schema: "public", Name: "m_product", SuggestedWatermark: &wm, RowEstimate: 5},
	}
	got := FormatSummary(sel)
	if got != "c_bpartner, m_product" {
		t.Errorf("got %q", got)
	}
}

func TestFormatSummaryEmpty(t *testing.T) {
	if FormatSummary(nil) != "" {
		t.Error("nil selection should yield empty string")
	}
}

func TestFormatSummarySingle(t *testing.T) {
	sel := []client.JdbcTable{{Schema: "public", Name: "c_order"}}
	if got := FormatSummary(sel); got != "c_order" {
		t.Errorf("single table: got %q", got)
	}
}

func TestSelectableIsFalseForATableWithUnsupportedColumns(t *testing.T) {
	clean := client.JdbcTable{Schema: "ERP", Name: "ORDERS"}
	bad := client.JdbcTable{Schema: "ERP", Name: "DOCS", UnsupportedColumns: []string{"BODY BLOB"}}
	if !Selectable(clean) {
		t.Error("a clean table must be selectable")
	}
	if Selectable(bad) {
		t.Error("a table with an unsupported column must not be selectable")
	}
}

func TestPickerLabelMarksAnIncompatibleTableAndNamesTheColumn(t *testing.T) {
	bad := client.JdbcTable{Schema: "ERP", Name: "DOCS", UnsupportedColumns: []string{"BODY BLOB"}}
	got := PickerLabel(bad, true)
	if !strings.HasPrefix(got, "[!]") {
		t.Errorf("label must start with [!], got %q", got)
	}
	if !strings.Contains(got, "ERP.DOCS") || !strings.Contains(got, "BODY BLOB") {
		t.Errorf("label must name the table and the column+type, got %q", got)
	}
	if strings.Contains(got, "[x]") || strings.Contains(got, "[ ]") {
		t.Errorf("an incompatible table must not look selectable, got %q", got)
	}
}

func TestPickerLabelOfACleanTableKeepsTheSelectionMark(t *testing.T) {
	clean := client.JdbcTable{Schema: "ERP", Name: "ORDERS", RowEstimate: 2}
	if got := PickerLabel(clean, true); !strings.HasPrefix(got, "[x]") {
		t.Errorf("selected clean table, got %q", got)
	}
	if got := PickerLabel(clean, false); !strings.HasPrefix(got, "[ ]") {
		t.Errorf("unselected clean table, got %q", got)
	}
}
