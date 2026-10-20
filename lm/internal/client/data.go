package client

import (
	"fmt"
	"net/url"
)

// DataTable summarizes one dynamic table landed in the hub: the mapping that
// wrote it, its schema-qualified name, and its row count.
type DataTable struct {
	MappingID   string `json:"mappingId"`
	TableSchema string `json:"tableSchema"`
	TableName   string `json:"tableName"`
	RowCount    *int64 `json:"rowCount"` // the caller's rows; nil when the hub could not count (the table is gone)
}

// DataRowGet fetches one ingested row by its content hash via
// GET /api/platform/data/row/{hash}.
func (c *Client) DataRowGet(hash string) (map[string]any, error) {
	var out map[string]any
	if err := c.GET("/api/platform/data/row/"+hash, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// DataTables lists every dynamic table landed in the hub via
// GET /api/platform/data/tables.
func (c *Client) DataTables() ([]DataTable, error) {
	var out []DataTable
	if err := c.GET("/api/platform/data/tables", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// DataTableRows reads one page of the caller's rows in the dynamic table registered under
// mappingID, in landing order (GET /api/platform/data/tables/{mappingId}/rows). The rows keep the
// table's column order, as an entity's do.
func (c *Client) DataTableRows(mappingID string, limit, offset int) ([]EntityRow, error) {
	var rows []EntityRow
	path := fmt.Sprintf("/api/platform/data/tables/%s/rows?limit=%d&offset=%d", url.PathEscape(mappingID), limit, offset)
	if err := c.GET(path, &rows); err != nil {
		return nil, err
	}
	return rows, nil
}

// Rows renders RowCount for a table cell: "unknown" when the hub could not count.
func (t DataTable) Rows() string {
	if t.RowCount == nil {
		return "unknown"
	}
	return fmt.Sprintf("%d", *t.RowCount)
}
