package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// CsvIngestOpts controls a one-shot CSV ingest: the destination connector name
// and métier scope, tags to apply, whether RAG indexing is enabled, and an
// optional cap on rows read.
type CsvIngestOpts struct {
	Name       string
	Scope      string
	Tags       []string
	Limit      int
}

// CsvIngestPromote names one column the ingest promoted to its own typed field
// instead of leaving it in the raw row payload.
type CsvIngestPromote struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

// CsvIngestResult reports the outcome of a CSV ingest: the connector/endpoint
// created, the landed table, the row/chunk counts, any promoted columns, and
// warnings.
type CsvIngestResult struct {
	ConnectorID    string             `json:"connectorId"`
	EndpointID     string             `json:"endpointId"`
	TableName      string             `json:"tableName"`
	RowsRead       int                `json:"rowsRead"`
	RowsWritten    int                `json:"rowsWritten"`
	RowsSkipped    int                `json:"rowsSkipped"`
	PromoteColumns []CsvIngestPromote `json:"promoteColumns"`
	Warnings       []string           `json:"warnings"`
	AskableScopes  []string           `json:"askableScopes"`
	ChatAvailable  bool               `json:"chatAvailable"`
	Refreshed      bool               `json:"refreshed"`
	Scope          string             `json:"-"` // echoed back for output
	SourceFile     string             `json:"-"`
}

// CsvIngest uploads file as a multipart form to
// POST /api/platform/sources/csv/ingest, which creates (or reuses) a csv-file
// connector and runs one synchronous ingest pass.
func (c *Client) CsvIngest(file *os.File, sourcePath string, opts CsvIngestOpts) (*CsvIngestResult, error) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)

	fileField, err := mw.CreateFormFile("file", filepath.Base(file.Name()))
	if err != nil {
		return nil, err
	}
	if _, err := io.Copy(fileField, file); err != nil {
		return nil, err
	}

	addField := func(name, value string) {
		if value != "" {
			_ = mw.WriteField(name, value)
		}
	}
	addField("name", opts.Name)
	addField("scope", opts.Scope)
	if len(opts.Tags) > 0 {
		addField("tags", strings.Join(opts.Tags, ","))
	}
	if opts.Limit > 0 {
		addField("limit", strconv.Itoa(opts.Limit))
	}
	_ = mw.Close()

	url := strings.TrimRight(c.ctx.Server, "/") + "/api/platform/sources/csv/ingest"
	req, err := http.NewRequest("POST", url, &buf)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	if err := c.authorize(req); err != nil {
		return nil, err
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return nil, &APIError{Status: resp.StatusCode, Body: string(body), RetryAfter: resp.Header.Get("Retry-After")}
	}

	var result CsvIngestResult
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, fmt.Errorf("parse response: %w (body=%s)", err, string(body))
	}
	result.Scope = opts.Scope
	result.SourceFile = sourcePath
	return &result, nil
}
