package client

import "net/url"

// SchemaColumn / SchemaTable / SchemaCard mirror the JSON of io.lumnik.rag.ask.SchemaCard.
type SchemaColumn struct {
	Name   string `json:"name"`
	PgType string `json:"pgType"`
	Label  string `json:"label"`
}

// SchemaTable is one discovered table's columns, mirroring the JSON of
// io.lumnik.rag.ask.SchemaCard.
type SchemaTable struct {
	Name    string         `json:"name"`
	Label   string         `json:"label"`
	Columns []SchemaColumn `json:"columns"`
}

// SchemaCard is the discovered schema for one métier scope: every table the RAG
// text-to-SQL guard is allowed to reference, mirroring the JSON of
// io.lumnik.rag.ask.SchemaCard.
type SchemaCard struct {
	Scope  string        `json:"scope"`
	Tables []SchemaTable `json:"tables"`
}

// RediscoverResult mirrors io.lumnik.rag.ask.DiscoveryResult.
type RediscoverResult struct {
	Scope       string `json:"scope"`
	TableCount  int    `json:"tableCount"`
	ColumnCount int    `json:"columnCount"`
	Enriched    bool   `json:"enriched"`
	SourceType  string `json:"sourceType"`
}

// RagSchema loads the persisted SchemaCard for a scope.
func (c *Client) RagSchema(scope string) (*SchemaCard, error) {
	var out SchemaCard
	if err := c.GET("/api/platform/rag/schema?scope="+url.QueryEscape(scope), &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// RagRediscover (re)builds + persists the SchemaCard for a scope.
func (c *Client) RagRediscover(scope string) (*RediscoverResult, error) {
	var out RediscoverResult
	if err := c.POST("/api/platform/rag/rediscover?scope="+url.QueryEscape(scope), map[string]any{}, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
