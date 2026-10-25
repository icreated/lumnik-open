package client

import (
	"context"
	"net/url"
	"strconv"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// RunList fetches recent runs via GET /api/platform/runs, newest first, narrowed by whichever
// filter is given — the same shape as DlqList's. Empty means "every": no filter at all is the
// global ledger. Both ride the QUERY because the ledger is global, and narrowing a page after it
// arrives answers about the hub's newest N runs rather than about what was asked for.
//
// endpointID is the finer half of the same address: a connector writes one run row per endpoint,
// so only it can answer "this table's runs". The server clamps limit to RunsPageCap.
func (c *Client) RunList(connectorID, endpointID string, limit int) ([]types.Run, error) {
	q := url.Values{}
	if connectorID != "" {
		q.Set("connector", connectorID)
	}
	if endpointID != "" {
		q.Set("endpoint", endpointID)
	}
	q.Set("limit", strconv.Itoa(limit))
	var out []types.Run
	if err := c.GET("/api/platform/runs?"+q.Encode(), &out); err != nil {
		return nil, err
	}
	return out, nil
}

// RunGetCtx is RunGet bounded by ctx — what a poll needs, so a blocked read cannot outlive --timeout.
func (c *Client) RunGetCtx(ctx context.Context, id string) (types.Run, error) {
	var out types.Run
	if err := c.doOnCtx(ctx, c.http, 0, "GET", "/api/platform/runs/"+id, nil, &out); err != nil {
		return types.Run{}, err
	}
	return out, nil
}

// RunGet fetches a single run by id.
func (c *Client) RunGet(id string) (types.Run, error) {
	var out types.Run
	if err := c.GET("/api/platform/runs/"+id, &out); err != nil {
		return types.Run{}, err
	}
	return out, nil
}

// RunCancel asks the hub to cancel a run via POST /api/platform/runs/{id}/cancel and returns the hub's
// status word: "cancelled" for a run that had not started (it is already ended), "cancellation
// requested" for one in flight. For the latter a 202 only means the request was
// recorded — the chunk loop reads the flag at the next chunk boundary, so
// this never guarantees the run has stopped. A non-2xx (already ended, or no
// chunk loop is watching it) surfaces the hub's own {"error": …} message via
// APIError, unchanged.
func (c *Client) RunCancel(id string) (string, error) {
	var out struct {
		Status string `json:"status"`
	}
	if err := c.POST("/api/platform/runs/"+id+"/cancel", map[string]any{}, &out); err != nil {
		return "", err
	}
	return out.Status, nil
}
