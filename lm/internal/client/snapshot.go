package client

import (
	"context"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"strings"
)

// snapshotErrorBodyCap bounds how much of a refusal body is kept: the success body is streamed,
// and an error body must not be the one read that grows with the server's answer.
const snapshotErrorBodyCap = 64 << 10

// EntitySnapshotDownload streams one entity's canonical CSV snapshot from the one-shot download
// door (GET /api/exports/entities/{name}/download) and returns the byte count copied.
//
// open is called only once the hub has answered 200 with a CSV body, so a refusal (403 scope,
// 404, a login page from a proxy) never makes the caller create anything. The copy runs on the
// stream client — a snapshot legitimately outlasts the 30s request budget — so ctx is what
// bounds it. A body cut short (a truncated chunked stream, a short Content-Length) is an error,
// never a smaller success.
func (c *Client) EntitySnapshotDownload(ctx context.Context, name string, open func() (io.Writer, error)) (int64, error) {
	path := "/api/exports/entities/" + url.PathEscape(name) + "/download"
	req, err := http.NewRequestWithContext(ctx, "GET", strings.TrimRight(c.ctx.Server, "/")+path, nil)
	if err != nil {
		return 0, err
	}
	// JSON fallback: the refusals (403/404) are JSON, and a csv-only Accept could 406 them.
	req.Header.Set("Accept", "text/csv, application/json;q=0.9")
	if err := c.authorize(req); err != nil {
		return 0, err
	}
	resp, err := c.stream.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		data, _ := io.ReadAll(io.LimitReader(resp.Body, snapshotErrorBodyCap))
		return 0, &APIError{Status: resp.StatusCode, Body: string(data), RetryAfter: resp.Header.Get("Retry-After")}
	}
	// Only a 200 is the whole snapshot: a 206 Partial Content or any other 2xx/3xx carrying CSV
	// is not the exact artifact the door promises, and must not pass for it.
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("the hub answered %d, not 200 — not a complete snapshot", resp.StatusCode)
	}
	if mt, _, _ := mime.ParseMediaType(resp.Header.Get("Content-Type")); mt != "text/csv" {
		return 0, fmt.Errorf("the hub answered %d with %q, not a CSV snapshot", resp.StatusCode,
			resp.Header.Get("Content-Type"))
	}
	w, err := open()
	if err != nil {
		return 0, err
	}
	n, err := io.Copy(w, resp.Body)
	if err != nil {
		return n, fmt.Errorf("snapshot download stopped after %d bytes: %w", n, err)
	}
	if resp.ContentLength >= 0 && n != resp.ContentLength {
		return n, fmt.Errorf("snapshot cut short: %d of %d bytes", n, resp.ContentLength)
	}
	return n, nil
}
