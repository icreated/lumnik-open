package client

import (
	"net/url"
	"strconv"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// DlqList fetches dead-letter entries via GET /api/platform/dlq, optionally
// filtered by connector and/or run id; the server clamps limit to 200.
func (c *Client) DlqList(connectorID, runID string, limit int) ([]types.DeadLetter, error) {
	q := url.Values{}
	if connectorID != "" {
		q.Set("connector", connectorID)
	}
	if runID != "" {
		q.Set("run", runID)
	}
	if limit > 0 {
		q.Set("limit", strconv.Itoa(limit)) // forward the requested size; the server clamps it (<=200)
	}
	path := "/api/platform/dlq"
	if len(q) > 0 {
		path += "?" + q.Encode()
	}
	var out []types.DeadLetter
	if err := c.GET(path, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// DlqDiscard marks a dead-letter entry discarded (won't be retried) via
// POST /api/platform/dlq/{id}/discard.
func (c *Client) DlqDiscard(id string) error {
	return c.POST("/api/platform/dlq/"+id+"/discard", map[string]any{}, nil)
}

// DlqReplayed marks an entry replayed by newRunID, the run that carried the row again: the hub
// requires it (#1377).
func (c *Client) DlqReplayed(id, newRunID string) error {
	return c.POST("/api/platform/dlq/"+id+"/replayed?newRunId="+url.QueryEscape(newRunID), map[string]any{}, nil)
}

// DlqGet fetches one dead-letter entry by id via GET /api/platform/dlq/{id}.
func (c *Client) DlqGet(id string) (types.DeadLetter, error) {
	var out types.DeadLetter
	if err := c.GET("/api/platform/dlq/"+id, &out); err != nil {
		return types.DeadLetter{}, err
	}
	return out, nil
}
