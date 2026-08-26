package resources

import (
	"errors"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func TestDeriveHeadline(t *testing.T) {
	cases := []struct {
		answer   string
		rowCount int
		want     string
	}{
		{"47 orders worth €312,480 were never invoiced.", 47, "€312,480"},
		{"Found 312 480 unpaid across the year.", 5, "312 480"},
		{"Just a few matches, nothing big.", 3, "3 rows"},
		{"One match only.", 1, "1 row"},
		{"", 0, "0 rows"},
	}
	for _, tc := range cases {
		if got := deriveHeadline(tc.answer, tc.rowCount); got != tc.want {
			t.Errorf("deriveHeadline(%q, %d) = %q, want %q", tc.answer, tc.rowCount, got, tc.want)
		}
	}
}

func TestSqlBadge(t *testing.T) {
	tag, label := sqlBadge("Here are the 47 matching orders.")
	if tag != "green" || !strings.Contains(label, "SELECT-only") {
		t.Errorf("normal badge = (%q, %q), want green / SELECT-only", tag, label)
	}
	tag, label = sqlBadge("I couldn't translate that into a safe query.")
	if tag != "red" || !strings.Contains(label, "rejected") {
		t.Errorf("rejected badge = (%q, %q), want red / rejected", tag, label)
	}
}

func TestRowsToCSV(t *testing.T) {
	got := rowsToCSV([]string{"doc", "amt"}, [][]string{{"SO-9", "312480"}, {"SO-10", "5"}})
	want := "doc,amt\nSO-9,312480\nSO-10,5\n"
	if got != want {
		t.Errorf("rowsToCSV = %q, want %q", got, want)
	}
}

func TestFriendlyAskError(t *testing.T) {
	// 503 — analytic engine (AskPort) not on the classpath
	msg := friendlyAskError(&client.APIError{Status: 503, Body: `{"error":"No AskPort impl on classpath"}`}, "atelier")
	if !strings.Contains(msg, "unavailable") {
		t.Errorf("503 msg = %q, want 'unavailable'", msg)
	}
	// 500 — returns the real error + hint (the dominant cause: no discovered SchemaCard for REST/CSV scopes)
	msg = friendlyAskError(&client.APIError{Status: 500, Body: "boom"}, "atelier")
	if !strings.Contains(msg, "discovered") || !strings.Contains(msg, "Hint") {
		t.Errorf("500 msg = %q, want 'discovered' + 'Hint'", msg)
	}
	// network / non-API error
	msg = friendlyAskError(errors.New("connection refused"), "atelier")
	if !strings.Contains(msg, "reach") {
		t.Errorf("network msg = %q, want 'reach'", msg)
	}
}
