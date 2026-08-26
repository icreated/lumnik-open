package tui

import (
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
