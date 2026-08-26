package client

// DataTable summarizes one dynamic table landed in the hub: the mapping that
// wrote it, its schema-qualified name, and its row count.
type DataTable struct {
	MappingID   string `json:"mappingId"`
	TableSchema string `json:"tableSchema"`
	TableName   string `json:"tableName"`
	RowCount    int64  `json:"rowCount"`
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
