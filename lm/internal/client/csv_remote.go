package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
)

// CsvIngestRemote posts a transport JSON (sftp/s3) instead of uploading a local file.
func (c *Client) CsvIngestRemote(transport map[string]any, name, scope string, tags []string, limit int) (*CsvIngestResult, error) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)

	transportJSON, err := json.Marshal(transport)
	if err != nil {
		return nil, fmt.Errorf("marshal transport: %w", err)
	}
	_ = mw.WriteField("transport", string(transportJSON))

	if name != "" {
		_ = mw.WriteField("name", name)
	}
	if scope != "" {
		_ = mw.WriteField("scope", scope)
	}
	if len(tags) > 0 {
		_ = mw.WriteField("tags", strings.Join(tags, ","))
	}
	if limit > 0 {
		_ = mw.WriteField("limit", strconv.Itoa(limit))
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
	result.Scope = scope
	return &result, nil
}
