package client

import (
	"encoding/json"
	"net/url"
)

// ViewColumn is a resolved, card-backed column: canonical name + human label + pg type.
type ViewColumn struct {
	Name   string `json:"name"`
	Label  string `json:"label"`
	PgType string `json:"pgType"`
}

// ViewResult is a rendered kind:View: title, ordered columns, and rows aligned to those columns.
// Truncated is the hub saying its row cap dropped matching rows.
type ViewResult struct {
	Title     string       `json:"title"`
	Columns   []ViewColumn `json:"columns"`
	Rows      [][]string   `json:"rows"`
	Truncated bool         `json:"truncated"`
}

type viewWire struct {
	Title     string                       `json:"title"`
	Columns   []ViewColumn                 `json:"columns"`
	Rows      []map[string]json.RawMessage `json:"rows"`
	Truncated bool                         `json:"truncated"`
}

// ViewRender posts a kind:View manifest to /api/views/render and returns the rendered surface.
// Filters ride repeated `filter` query params (col=value | col~text).
func (c *Client) ViewRender(yaml []byte, filters []string) (*ViewResult, error) {
	p := "/api/views/render"
	if len(filters) > 0 {
		q := url.Values{}
		for _, f := range filters {
			q.Add("filter", f)
		}
		p += "?" + q.Encode()
	}
	data, err := c.doRaw("POST", p, "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}

	var wire viewWire
	if err := json.Unmarshal(data, &wire); err != nil {
		return nil, err
	}
	rows := make([][]string, 0, len(wire.Rows))
	for _, obj := range wire.Rows {
		row := make([]string, len(wire.Columns))
		for j, col := range wire.Columns {
			row[j] = stringifyJSON(obj[col.Name])
		}
		rows = append(rows, row)
	}
	return &ViewResult{Title: wire.Title, Columns: wire.Columns, Rows: rows, Truncated: wire.Truncated}, nil
}

// DetailSource is one entry of the _conflicts ledger for a column: a source's value, or — for a
// lookup whose key is ambiguous — one duplicate row's value. Value is nil when the ledger carries an
// explicit null (a lookup row with nothing to say), which is NOT the empty string.
type DetailSource struct {
	Src   string  `json:"src"`
	Value *string `json:"value"`
}

// StrPtr is the literal-friendly constructor for a present value.
func StrPtr(s string) *string { return &s }

// Display is how a ledger line shows the value: an absent one reads "(empty)", never a blank, so a
// null cannot pass for the empty string. The real "" stays "".
func (s DetailSource) Display() string {
	if s.Value == nil {
		return "(empty)"
	}
	return *s.Value
}

// DetailConflict is one column where the fused sources conflicted or a lookup key was ambiguous.
type DetailConflict struct {
	Column  string         `json:"column"`
	Label   string         `json:"label"`
	Sources []DetailSource `json:"sources"`
}

// DetailField is one rendered field of a record.
type DetailField struct {
	Name  string `json:"name"`
	Label string `json:"label"`
	Value string `json:"value"`
}

// ViewDetailResult is a rendered kind:View record: its fields plus the _conflicts ledger.
type ViewDetailResult struct {
	Title          string           `json:"title"`
	Key            string           `json:"key"`
	Fields         []DetailField    `json:"fields"`
	ConflictLedger bool             `json:"conflictLedger"`
	Conflicts      []DetailConflict `json:"conflicts"`
}

// ViewDetail posts a kind:View manifest to /api/views/detail?key= and returns the drill-down record.
func (c *Client) ViewDetail(yaml []byte, key string) (*ViewDetailResult, error) {
	q := url.Values{}
	q.Set("key", key)
	data, err := c.doRaw("POST", "/api/views/detail?"+q.Encode(), "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	var wire struct {
		Title  string `json:"title"`
		Key    string `json:"key"`
		Fields []struct {
			Name  string          `json:"name"`
			Label string          `json:"label"`
			Value json.RawMessage `json:"value"`
		} `json:"fields"`
		ConflictLedger bool             `json:"conflictLedger"`
		Conflicts      []DetailConflict `json:"conflicts"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return nil, err
	}
	res := &ViewDetailResult{
		Title:          wire.Title,
		Key:            wire.Key,
		ConflictLedger: wire.ConflictLedger,
		Conflicts:      wire.Conflicts,
	}
	for _, f := range wire.Fields {
		res.Fields = append(res.Fields, DetailField{Name: f.Name, Label: f.Label, Value: stringifyJSON(f.Value)})
	}
	return res, nil
}

// ViewSummary is one entry of the saved-view store listing.
type ViewSummary struct {
	Name  string `json:"name"`
	Scope string `json:"scope"`
	Title string `json:"title"`
}

// ViewsList lists the saved views visible to the caller, optionally narrowed to one scope.
func (c *Client) ViewsList(scope string) ([]ViewSummary, error) {
	p := "/api/views"
	if scope != "" {
		q := url.Values{}
		q.Set("scope", scope)
		p += "?" + q.Encode()
	}
	data, err := c.doRaw("GET", p, "", "application/json", nil)
	if err != nil {
		return nil, err
	}
	var views []ViewSummary
	if err := json.Unmarshal(data, &views); err != nil {
		return nil, err
	}
	return views, nil
}

// ViewGet fetches one saved view's manifest YAML, verbatim.
func (c *Client) ViewGet(name string) ([]byte, error) {
	// JSON fallback in Accept: error bodies (403/404) are JSON — a yaml-only Accept could 406 them.
	return c.doRaw("GET", "/api/views/"+url.PathEscape(name), "",
		"application/yaml, application/json;q=0.9", nil)
}

// ViewApplyResult is the hub's confirmation of a validate-then-upsert into the
// saved-view store.
type ViewApplyResult struct {
	Name     string            `json:"name"`
	Scope    string            `json:"scope"`
	Warnings []ValidationError `json:"warnings"` // applied, and worth saying (a link target not there yet)
}

// ViewApply validates and upserts a kind:View manifest into the saved-view store.
func (c *Client) ViewApply(manifest []byte) (*ViewApplyResult, error) {
	data, err := c.doRaw("POST", "/api/views/apply", "application/yaml", "application/json", manifest)
	if err != nil {
		return nil, err
	}
	var result ViewApplyResult
	if err := json.Unmarshal(data, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// ViewDelete removes one saved view for good — the delete gesture #487 gave kind:Process,
// missing here (#507). connector.saved_view carries no `enabled` column, so there is no
// reversible half the way ProcessSetEnabled/SetWorkflowEnabled have one.
func (c *Client) ViewDelete(name string) error {
	return c.DELETE("/api/views/" + url.PathEscape(name))
}

// ViewGenerate asks the hub's AI to write a kind:View manifest over a scope from a
// natural-language description. Returns the YAML text verbatim (comment line included).
func (c *Client) ViewGenerate(scope, description string) (string, error) {
	payload, err := json.Marshal(map[string]string{"scope": scope, "description": description})
	if err != nil {
		return "", err
	}
	// Explicit q-values (header order at equal quality is not spec-guaranteed): errors
	// (incl. the 401 thrown before the method runs) negotiate to JSON deterministically;
	// success is explicitly application/yaml server-side regardless.
	data, err := c.doRawSlow("POST", "/api/views/generate", "application/json",
		"application/json, application/yaml;q=0.9", payload)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
