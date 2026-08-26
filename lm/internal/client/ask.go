package client

import (
	"bytes"
	"encoding/json"
	"strings"
)

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
}

// analyticWire mirrors the platform AnalyticResponse JSON
// ({answer, sql, rowCount, rows:[{...}]}). Rows stay raw so we can recover the
// column order that unmarshalling into a Go map would discard.
type analyticWire struct {
	Answer   string            `json:"answer"`
	SQL      string            `json:"sql"`
	RowCount int               `json:"rowCount"`
	Rows     []json.RawMessage `json:"rows"`
	DataAsOf string            `json:"dataAsOf"`
}

// RagAsk runs an analytic question over the ingested hub via mode=analytic.
// scope is sent only when non-empty (the backend defaults it to "system").
func (c *Client) RagAsk(question, scope string) (*AskResult, error) {
	body := map[string]any{"question": question, "mode": "analytic"}
	if scope != "" {
		body["scope"] = scope
	}
	var wire analyticWire
	if err := c.POSTSlow("/api/platform/rag/chat", body, &wire); err != nil {
		return nil, err
	}
	cols, rows := parseRows(wire.Rows)
	return &AskResult{
		Answer:   wire.Answer,
		SQL:      wire.SQL,
		RowCount: wire.RowCount,
		Columns:  cols,
		Rows:     rows,
		DataAsOf: wire.DataAsOf,
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
