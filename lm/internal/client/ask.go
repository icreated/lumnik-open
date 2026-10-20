package client

import (
	"encoding/json"
	"os"
	"strings"
)

// Disputed is a fused record the answer filtered on while its sources disagree (#874).
type Disputed struct {
	Key      string           `json:"key"`
	Column   string           `json:"column"`
	Label    string           `json:"label"`
	Matched  string           `json:"matched"`
	Retained *string          `json:"retained"` // nil when the fused record holds no value
	Counted  bool             `json:"counted"`
	Sources  []DisputedSource `json:"sources"`
}

// DisputedSource is one ledger entry — the same shape the record detail carries.
type DisputedSource = DetailSource

// AskResult is the analytic (text-to-SQL) answer: the LLM's plain answer, the
// SQL that ran, and the result rows flattened to ordered string columns ready
// for table display and CSV export.
type AskResult struct {
	Answer   string
	SQL      string
	RowCount int
	Columns  []string   // ordered column names (taken from the first row)
	Rows     [][]string // each row aligned to Columns, values stringified
	DataAsOf string     // ISO end of the scope's last completed run; "" when unknown
	// OtherDoors names, as hub tokens ("lifecycle", "conflicts"), the doors that CAN answer when
	// this one abstains; usually empty. Rendering a token is each surface's own job — see the
	// hub's AskResult for why the wire carries tokens and not commands.
	OtherDoors       []string
	Disputed         []Disputed
	DisputedTruncated bool
	// Abstained is the hub's structural mark of a refusal (#915) — a withheld or unwritten answer is
	// one, and still carries its rows. Never recognised by what the answer's words say.
	Abstained bool
	// records are the rows as the hub typed them, which -o json|yaml prints: Rows is their display form.
	records []EntityRow
}

// analyticWire mirrors the platform AnalyticResponse JSON
// ({answer, sql, rowCount, rows:[{...}]}). A row is an EntityRow, which keeps
// the column order that unmarshalling into a Go map would discard.
type analyticWire struct {
	Answer            string      `json:"answer"`
	SQL               string      `json:"sql"`
	RowCount          int         `json:"rowCount"`
	Rows              []EntityRow `json:"rows"`
	DataAsOf          string      `json:"dataAsOf"`
	OtherDoors        []string    `json:"otherDoors"`
	Disputed          []Disputed  `json:"disputed"`
	DisputedTruncated bool        `json:"disputedTruncated"`
	Abstained         bool        `json:"abstained"`
}

// askDocument is AskResult as -o json|yaml prints it (#1105): the hub's field names in the hub's order,
// the rows as the hub typed them with their columns added beside them, and an empty list as [] — never
// null. understoodAs is left out: lm sends no previous question, so the hub never resolves one.
type askDocument struct {
	Answer            string      `json:"answer" yaml:"answer"`
	SQL               string      `json:"sql" yaml:"sql"`
	RowCount          int         `json:"rowCount" yaml:"rowCount"`
	Columns           []string    `json:"columns" yaml:"columns"`
	Rows              []EntityRow `json:"rows" yaml:"rows"`
	DataAsOf          *string     `json:"dataAsOf" yaml:"dataAsOf"` // null when unknown, as the hub sends it
	OtherDoors        []string    `json:"otherDoors" yaml:"otherDoors"`
	Disputed          []Disputed  `json:"disputed" yaml:"disputed"`
	DisputedTruncated bool        `json:"disputedTruncated" yaml:"disputedTruncated"`
	Abstained         bool        `json:"abstained" yaml:"abstained"`
}

func (r AskResult) document() askDocument {
	var asOf *string
	if r.DataAsOf != "" {
		asOf = &r.DataAsOf
	}
	return askDocument{
		Answer: r.Answer, SQL: r.SQL, RowCount: r.RowCount,
		Columns: orEmpty(r.Columns), Rows: orEmpty(r.records), DataAsOf: asOf,
		OtherDoors: orEmpty(r.OtherDoors), Disputed: orEmpty(r.Disputed),
		DisputedTruncated: r.DisputedTruncated, Abstained: r.Abstained,
	}
}

// MarshalJSON writes the analytic result document.
func (r AskResult) MarshalJSON() ([]byte, error) { return jsonText(r.document()) }

// MarshalYAML writes the same document as YAML.
func (r AskResult) MarshalYAML() (any, error) { return r.document(), nil }

func orEmpty[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}

// RagAsk runs an analytic question over the ingested hub via mode=analytic.
// scope is sent only when non-empty (the backend defaults it to "system"); lang, the answer
// language, likewise — absent, the hub follows the question's own language (#879).
func (c *Client) RagAsk(question, scope, lang string) (*AskResult, error) {
	body := map[string]any{"question": question, "mode": "analytic"}
	if scope != "" {
		body["scope"] = scope
	}
	if lang != "" {
		body["lang"] = lang
	}
	var wire analyticWire
	if err := c.POSTSlow("/api/platform/rag/chat", body, &wire); err != nil {
		return nil, err
	}
	cols, rows := flattenRows(wire.Rows)
	return &AskResult{
		Answer:            wire.Answer,
		SQL:               wire.SQL,
		RowCount:          wire.RowCount,
		Columns:           cols,
		Rows:              rows,
		DataAsOf:          wire.DataAsOf,
		OtherDoors:        wire.OtherDoors,
		Disputed:          wire.Disputed,
		DisputedTruncated: wire.DisputedTruncated,
		Abstained:         wire.Abstained,
		records:           wire.Rows,
	}, nil
}

// flattenRows turns the rows into ordered columns + string rows. Column order is taken
// from the first row (PostgreSQL result order, preserved by the server's LinkedHashMap).
// Later rows are aligned to those columns by key.
func flattenRows(records []EntityRow) ([]string, [][]string) {
	var cols []string
	if len(records) > 0 {
		cols = records[0].Columns()
	}
	out := make([][]string, 0, len(records))
	for _, r := range records {
		row := make([]string, len(cols))
		for j, col := range cols {
			row[j] = r.Cell(col)
		}
		out = append(out, row)
	}
	return cols, out
}

// stringifyJSON renders a scalar JSON value as a plain string: null → "",
// strings unquoted, numbers/bools/nested kept as their compact JSON text.
func stringifyJSON(raw json.RawMessage) string {
	s := strings.TrimSpace(string(raw))
	if s == "" || s == "null" {
		return ""
	}
	if s[0] == '"' {
		var str string
		if json.Unmarshal(raw, &str) == nil {
			return str
		}
	}
	return s
}

// AnswerLang is the answer language to send: explicit when given, else the language of the
// POSIX message locale, which LC_ALL overrides, then LC_MESSAGES, then LANG (#879).
func AnswerLang(explicit string) string {
	if explicit != "" {
		return explicit
	}
	for _, v := range []string{"LC_ALL", "LC_MESSAGES", "LANG"} {
		if locale := os.Getenv(v); locale != "" {
			return LangFromLocale(locale)
		}
	}
	return ""
}

// LangFromLocale reads the two-letter language of a POSIX locale such as LANG
// ("fr_FR.UTF-8" → "fr"). A locale that names no language ("C", "POSIX", "") gives "".
func LangFromLocale(locale string) string {
	lang := strings.ToLower(locale)
	if i := strings.IndexAny(lang, "_.@"); i >= 0 {
		lang = lang[:i]
	}
	if len(lang) != 2 || lang[0] < 'a' || lang[0] > 'z' || lang[1] < 'a' || lang[1] > 'z' {
		return ""
	}
	return lang
}
