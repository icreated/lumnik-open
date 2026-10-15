package resources

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func TestLedgerLinesShowsAnAbsentValueAsEmptyAndKeepsTheRealEmptyString(t *testing.T) {
	var b strings.Builder
	ledgerLines(&b, []client.DetailConflict{{Column: "category_name", Sources: []client.DetailSource{
		{Src: "t_cat", Value: nil},
		{Src: "t_cat", Value: client.StrPtr("")},
		{Src: "t_cat", Value: client.StrPtr("Pumps")},
	}}})
	out := b.String()
	if !strings.Contains(out, "t_cat          (empty)\n") {
		t.Fatalf("a null must read (empty), got %q", out)
	}
	if !strings.Contains(out, "t_cat          \n") {
		t.Fatalf("the real empty string must stay blank, got %q", out)
	}
	if !strings.Contains(out, "Pumps") {
		t.Fatalf("value lost: %q", out)
	}
}
