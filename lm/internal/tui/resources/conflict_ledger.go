package resources

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/rivo/tview"
)

// ledgerLines writes the "which source said what" block shared by the :view record detail
// and the :conflicts describe panel. Server-derived strings are tview-escaped (a value
// containing '[red]' must render literally); alignment counts runes, not bytes.
func ledgerLines(b *strings.Builder, conflicts []client.DetailConflict) {
	for _, c := range conflicts {
		fmt.Fprintf(b, "%s:\n", tview.Escape(c.Column))
		for _, s := range c.Sources {
			srcPad := strings.Repeat(" ", max(0, 14-utf8.RuneCountInString(s.Src)))
			fmt.Fprintf(b, "    %s%s %s\n", tview.Escape(s.Src), srcPad, tview.Escape(s.Value))
		}
	}
}
