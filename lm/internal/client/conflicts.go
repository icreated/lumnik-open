package client

import "fmt"

// Conflict is one fused-entity row whose sources still disagree: which entity, which
// record, which columns, and what each source said for them.
type Conflict struct {
	Entity    string                    `json:"entity"`
	Key       string                    `json:"key"`
	Columns   []string                  `json:"columns"`
	Conflicts map[string][]DetailSource `json:"conflicts"`
}

// SourceNames lists the distinct sources involved in this row's disagreement, in the
// order the ledger first mentions them — the table's SOURCES column.
func (c Conflict) SourceNames() []string {
	seen := make(map[string]bool, len(c.Columns))
	var out []string
	for _, col := range c.Columns {
		for _, s := range c.Conflicts[col] {
			if !seen[s.Src] {
				seen[s.Src] = true
				out = append(out, s.Src)
			}
		}
	}
	return out
}

// Ledger shapes the row for the shared ledger renderer, keeping the server's column order.
func (c Conflict) Ledger() []DetailConflict {
	out := make([]DetailConflict, 0, len(c.Columns))
	for _, col := range c.Columns {
		out = append(out, DetailConflict{Column: col, Sources: c.Conflicts[col]})
	}
	return out
}

// ConflictList returns the open fusion conflicts across every entity the caller may
// read; the server silently skips entities outside the caller's scope grants.
func (c *Client) ConflictList(limit int) ([]Conflict, error) {
	var out []Conflict
	if err := c.GET(fmt.Sprintf("/api/entities/conflicts?limit=%d", limit), &out); err != nil {
		return nil, err
	}
	return out, nil
}
