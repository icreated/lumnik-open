package client

import "fmt"

// JdbcTestRequest is a JDBC connectivity check: connection URL and credentials.
type JdbcTestRequest struct {
	URL      string `json:"url"`
	User     string `json:"user"`
	Password string `json:"password"`
}

// JdbcTestResponse reports whether a JDBC connection succeeded, the detected
// dialect/server version, the round-trip latency in milliseconds, and the
// visible schemas.
type JdbcTestResponse struct {
	OK        bool     `json:"ok"`
	Dialect   string   `json:"dialect"`
	Version   string   `json:"version"`
	LatencyMs int64    `json:"latencyMs"`
	Schemas   []string `json:"schemas"`
}

// JdbcDiscoverRequest asks the hub to introspect a JDBC source's tables within
// one schema, optionally skipping name patterns or including views.
type JdbcDiscoverRequest struct {
	URL          string   `json:"url"`
	User         string   `json:"user"`
	Password     string   `json:"password"`
	Schema       string   `json:"schema"`
	SkipPatterns []string `json:"skipPatterns"`
	IncludeViews bool     `json:"includeViews"`
}

// JdbcColumn is one discovered JDBC column: name, JDBC type name, size, and
// nullability.
type JdbcColumn struct {
	Name     string `json:"name"`
	JdbcType string `json:"jdbcType"`
	Size     int    `json:"size"`
	Nullable bool   `json:"nullable"`
}

// JdbcTable is one discovered JDBC table: its primary key and columns, a
// server-suggested watermark column (nil if none fits), and an approximate row
// count.
type JdbcTable struct {
	Schema             string       `json:"schema"`
	Name               string       `json:"name"`
	PrimaryKey         []string     `json:"primaryKey"`
	Columns            []JdbcColumn `json:"columns"`
	SuggestedWatermark *string      `json:"suggestedWatermark"`
	RowEstimate        int64        `json:"rowEstimate"`
	// UnsupportedColumns lists, as "NAME TYPE", the columns the source's dialect cannot read. Such a
	// table is listed but cannot be added: the hub refuses it by name.
	UnsupportedColumns []string `json:"unsupportedColumns"`
}

// JdbcDiscoverResponse wraps the tables found by JdbcDiscover.
type JdbcDiscoverResponse struct {
	Tables []JdbcTable `json:"tables"`
}

// JdbcAddTable names one discovered table to onboard, with the watermark column
// and ingestion mode chosen for it.
type JdbcAddTable struct {
	Table     string `json:"table"`
	Watermark string `json:"watermark,omitempty"`
	Mode      string `json:"mode,omitempty"`
}

// JdbcAddRequest onboards one or more discovered JDBC tables as connectors:
// connection details, the tables to add, and the mapping template mode/schedule
// to apply to each.
type JdbcAddRequest struct {
	SourceName   string         `json:"sourceName"`
	URL          string         `json:"url"`
	User         string         `json:"user"`
	Password     string         `json:"password"`
	Schema       string         `json:"schema,omitempty"`
	Tables       []JdbcAddTable `json:"tables"`
	ScheduleCron string         `json:"scheduleCron,omitempty"`
}

// JdbcAddEntry is one connector created (or skipped) by JdbcAdd.
type JdbcAddEntry struct {
	ConnectorID   string `json:"connectorId"`
	ConnectorName string `json:"connectorName"`
	Status        string `json:"status"`
}

// JdbcAddResponse reports which tables JdbcAdd onboarded as new connectors and
// which it skipped (e.g. already onboarded).
type JdbcAddResponse struct {
	Created []JdbcAddEntry `json:"created"`
	Skipped []JdbcAddEntry `json:"skipped"`
}

// JdbcListedTable is one JDBC-sourced table already onboarded as a connector,
// with its last run timestamp, rows ingested, and status.
type JdbcListedTable struct {
	Table         string `json:"table"`
	ConnectorName string `json:"connectorName"`
	ConnectorID   string `json:"connectorId"`
	LastRunAt     string `json:"lastRunAt"`
	RowsIngested  int64  `json:"rowsIngested"`
	Status        string `json:"status"`
}

// JdbcListTablesResponse wraps the tables already onboarded from one named JDBC
// source.
type JdbcListTablesResponse struct {
	SourceName string            `json:"sourceName"`
	Tables     []JdbcListedTable `json:"tables"`
}

// JdbcTest checks connectivity to a JDBC source via
// POST /api/platform/sources/jdbc/test.
func (c *Client) JdbcTest(req JdbcTestRequest) (*JdbcTestResponse, error) {
	var out JdbcTestResponse
	if err := c.POST("/api/platform/sources/jdbc/test", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// JdbcDiscover introspects a JDBC source's tables via
// POST /api/platform/sources/jdbc/discover.
func (c *Client) JdbcDiscover(req JdbcDiscoverRequest) (*JdbcDiscoverResponse, error) {
	var out JdbcDiscoverResponse
	if err := c.POST("/api/platform/sources/jdbc/discover", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// JdbcAdd onboards discovered JDBC tables as connectors via
// POST /api/platform/sources/jdbc/add.
func (c *Client) JdbcAdd(req JdbcAddRequest) (*JdbcAddResponse, error) {
	var out JdbcAddResponse
	if err := c.POST("/api/platform/sources/jdbc/add", req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// JdbcListTables lists the tables already onboarded from a named JDBC source via
// GET /api/platform/sources/jdbc/{sourceName}/tables.
func (c *Client) JdbcListTables(sourceName string) (*JdbcListTablesResponse, error) {
	var out JdbcListTablesResponse
	if err := c.GET(fmt.Sprintf("/api/platform/sources/jdbc/%s/tables", sourceName), &out); err != nil {
		return nil, err
	}
	return &out, nil
}
