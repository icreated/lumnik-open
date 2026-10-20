package client

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// Endpoints lists a connector's endpoints via
// GET /api/platform/connectors/{connectorID}/endpoints.
func (c *Client) Endpoints(connectorID string) ([]types.Endpoint, error) {
	var out []types.Endpoint
	if err := c.GET("/api/platform/connectors/"+connectorID+"/endpoints", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// EndpointEnable flips one endpoint's enabled flag via
// PUT /api/platform/connectors/{connectorID}/endpoints/{endpointID}.
func (c *Client) EndpointEnable(connectorID, endpointID string, enabled bool) error {
	return c.PUT(
		fmt.Sprintf("/api/platform/connectors/%s/endpoints/%s", connectorID, endpointID),
		map[string]bool{"enabled": enabled},
		nil,
	)
}

// EndpointPurge deletes the rows an endpoint has landed via
// POST /api/platform/connectors/{connectorID}/endpoints/{endpointID}/purge,
// returning the number of rows deleted.
func (c *Client) EndpointPurge(connectorID, endpointID string) (int, error) {
	return c.endpointPurge(connectorID, endpointID, "")
}

// EndpointPurgeDryRun asks the same door with ?dryRun=true: the hub runs the same checks and
// refusals and returns how many rows a purge would delete, deleting none.
func (c *Client) EndpointPurgeDryRun(connectorID, endpointID string) (int, error) {
	return c.endpointPurge(connectorID, endpointID, "?dryRun=true")
}

func (c *Client) endpointPurge(connectorID, endpointID, query string) (int, error) {
	var out struct {
		Deleted int `json:"deleted"`
	}
	if err := c.POST(
		fmt.Sprintf("/api/platform/connectors/%s/endpoints/%s/purge%s", connectorID, endpointID, query),
		map[string]any{},
		&out,
	); err != nil {
		return 0, err
	}
	return out.Deleted, nil
}

// EndpointResetCursor clears an endpoint's stored read cursor via
// POST /api/platform/connectors/{connectorID}/endpoints/{endpointID}/reset-cursor,
// forcing the next run to re-read from the start.
func (c *Client) EndpointResetCursor(connectorID, endpointID string) error {
	return c.POST(
		fmt.Sprintf("/api/platform/connectors/%s/endpoints/%s/reset-cursor", connectorID, endpointID),
		map[string]any{},
		nil,
	)
}

// EndpointTest fires the one request a run would send at an endpoint via
// POST /api/platform/connectors/{connectorID}/endpoints/{endpointID}/test,
// and reports what came back without landing any data.
//
// There is no server-side "test them all": endpointID is one endpoint's UUID. The CLI resolves the
// list and calls this per endpoint, the way every other endpoint verb addresses one. (It used to
// send the literal "*" here as a test-all sentinel; the route binds a UUID, so every call 404'd —
// since the first commit.)
func (c *Client) EndpointTest(connectorID, endpointID string) (types.PreflightVerdict, error) {
	var out types.PreflightVerdict
	if err := c.POST(
		fmt.Sprintf("/api/platform/connectors/%s/endpoints/%s/test", connectorID, endpointID),
		map[string]any{},
		&out,
	); err != nil {
		return types.PreflightVerdict{}, err
	}
	return out, nil
}
