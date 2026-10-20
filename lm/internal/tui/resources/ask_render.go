package resources

import (
	"bytes"
	"encoding/csv"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/output"
)

// moneyRe matches a currency-prefixed amount (€312,480 / $1 234.50) or a
// thousands-grouped number (312 480 / 1.234.567) — the "gut-punch number".
var moneyRe = regexp.MustCompile(`[€$£]\s?\d[\d.,\s]*\d|\b\d{1,3}(?:[.,\s]\d{3})+\b`)

// deriveHeadline picks the punchiest text for the gold band: the first money /
// large grouped number in the answer, else a row-count summary.
func deriveHeadline(answer string, rowCount int) string {
	if m := strings.TrimSpace(moneyRe.FindString(answer)); m != "" {
		return m
	}
	return output.RowCount(rowCount)
}

// askRejected reports whether the agent declined to translate (SqlGuard reject).
// Mirrors AskToSqlAgent's degrade message ("I couldn't translate that ...").
func askRejected(answer string) bool {
	return strings.Contains(answer, "couldn't translate")
}

// sqlBadge returns the dynamic-color tag + label shown beside the generated SQL.
func sqlBadge(answer string) (tag, label string) {
	if askRejected(answer) {
		return "red", "[rejected ✗]"
	}
	return "green", "[SELECT-only ✓]"
}

// rowsToCSV renders columns + rows as RFC-4180 CSV text (the `e` export).
func rowsToCSV(cols []string, rows [][]string) string {
	var b bytes.Buffer
	w := csv.NewWriter(&b)
	_ = w.Write(cols)
	for _, r := range rows {
		_ = w.Write(r)
	}
	w.Flush()
	return b.String()
}

// friendlyAskError converts an ask failure into a calm, on-screen explanation.
// Contract: 503 (engine unavailable) returns a specific message; 403 and 429
// (scope refusal and rate limit) return the APIError message verbatim (Task 1
// made those honest); 404 says the chat is not served here; every other APIError returns the error + a hint about
// schema discovery (the dominant real-world cause for other 5xx errors);
// non-APIError wraps generically.
func friendlyAskError(err error, scope string) string {
	var apiErr *client.APIError
	if errors.As(err, &apiErr) {
		if apiErr.Status == 503 {
			return "Analytic engine unavailable — the platform has no AskToSql agent on the classpath."
		}
		// 404: the chat door is not served by this hub (a build without it, or the operator
		// switched the AI off). The hint below would name a cause the client cannot know.
		if apiErr.Status == 404 {
			return "The chat is not available on this hub."
		}
		// 403 and 429: return the honest error message as-is (no false cause).
		if apiErr.Status == 403 || apiErr.Status == 429 {
			return apiErr.Error()
		}
		// Other APIErrors: real error first, then a hint (the dominant cause is schema
		// discovery). Naming the scope keeps the hint actionable when several exist.
		return apiErr.Error() + fmt.Sprintf("\nHint: scope «%s» may have no discovered schema yet — ", scope) +
			"JDBC sources with a recognized data dictionary get business labels automatically; " +
			"REST and CSV label discovery is deferred."
	}
	return fmt.Sprintf("Can't reach the platform: %v", err)
}
