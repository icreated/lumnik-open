package client

import (
	"bytes"
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
}

// analyticWire mirrors the platform AnalyticResponse JSON
// ({answer, sql, rowCount, rows:[{...}]}). Rows stay raw so we can recover the
// column order that unmarshalling into a Go map would discard.
type analyticWire struct {
	Answer            string            `json:"answer"`
	SQL               string            `json:"sql"`
	RowCount          int               `json:"rowCount"`
	Rows              []json.RawMessage `json:"rows"`
	DataAsOf          string            `json:"dataAsOf"`
	OtherDoors        []string          `json:"otherDoors"`
	Disputed          []Disputed        `json:"disputed"`
	DisputedTruncated bool              `json:"disputedTruncated"`
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
	cols, rows := parseRows(wire.Rows)
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
	}, nil
}

// parseRows flattens the raw JSON row objects into ordered columns + string rows.
// Column order is taken from the first row (PostgreSQL result order, preserved by
// the server's LinkedHashMap). Later rows are aligned to those columns by key.
func parseRows(raw []json.RawMessage) ([]string, [][]string) {
	var cols []string
	seen := map[string]bool{}
	out := make([][]string, 0, len(raw))
	for i, rm := range raw {
		kvs := decodeObjectOrdered(rm)
		if i == 0 {
			for _, p := range kvs {
				if !seen[p.key] {
					seen[p.key] = true
					cols = append(cols, p.key)
				}
			}
		}
		byKey := make(map[string]string, len(kvs))
		for _, p := range kvs {
			byKey[p.key] = p.val
		}
		row := make([]string, len(cols))
		for j, col := range cols {
			row[j] = byKey[col]
		}
		out = append(out, row)
	}
	return cols, out
}

type orderedPair struct{ key, val string }

// decodeObjectOrdered walks a JSON object preserving key order via the streaming
// token decoder (json.Unmarshal into a map would randomise order).
func decodeObjectOrdered(rm json.RawMessage) []orderedPair {
	dec := json.NewDecoder(bytes.NewReader(rm))
	tok, err := dec.Token()
	if err != nil {
		return nil
	}
	if d, ok := tok.(json.Delim); !ok || d != '{' {
		return nil
	}
	var out []orderedPair
	for dec.More() {
		kt, err := dec.Token()
		if err != nil {
			break
		}
		key, _ := kt.(string)
		var val json.RawMessage
		if err := dec.Decode(&val); err != nil {
			break
		}
		out = append(out, orderedPair{key, stringifyJSON(val)})
	}
	return out
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
