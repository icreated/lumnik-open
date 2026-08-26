package client

import (
	"bufio"
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// SSEEvent is one parsed Server-Sent Event.
type SSEEvent struct {
	Event string
	Data  string
	ID    string
}

// SSEStream connects to an SSE endpoint and pushes events to ch until ctx is done
// or the connection fails.
func (c *Client) SSEStream(ctx context.Context, path string, topics []string, ch chan<- SSEEvent) error {
	u := strings.TrimRight(c.ctx.Server, "/") + path
	if len(topics) > 0 {
		params := url.Values{}
		for _, t := range topics {
			params.Add("topic", t)
		}
		sep := "?"
		if strings.Contains(u, "?") {
			sep = "&"
		}
		u = u + sep + params.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "text/event-stream")
	if err := c.authorize(req); err != nil {
		return err
	}
	// c.stream, not c.http: the json client's 30s Timeout bounds the whole
	// round trip including body reads and would silently kill this long-lived
	// connection. The caller's ctx (already on the request) owns the lifetime.
	resp, err := c.stream.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 400 {
		return fmt.Errorf("SSE upstream status %d", resp.StatusCode)
	}

	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	var cur SSEEvent
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if cur.Data != "" || cur.Event != "" {
				select {
				case <-ctx.Done():
					return ctx.Err()
				case ch <- cur:
				}
			}
			cur = SSEEvent{}
			continue
		}
		switch {
		case strings.HasPrefix(line, "data:"):
			val := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
			if cur.Data == "" {
				cur.Data = val
			} else {
				cur.Data = cur.Data + "\n" + val
			}
		case strings.HasPrefix(line, "event:"):
			cur.Event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "id:"):
			cur.ID = strings.TrimSpace(strings.TrimPrefix(line, "id:"))
		case strings.HasPrefix(line, ":"):
			// comment
		}
	}
	return scanner.Err()
}

// SSEStreamWithReconnect keeps reconnecting to the SSE endpoint on errors with a
// fixed backoff between attempts, until ctx is done.
func (c *Client) SSEStreamWithReconnect(ctx context.Context, path string, topics []string, ch chan<- SSEEvent, backoff time.Duration) {
	for {
		err := c.SSEStream(ctx, path, topics, ch)
		if ctx.Err() != nil {
			return
		}
		_ = err
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
	}
}
