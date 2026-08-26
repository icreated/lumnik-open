package client

import (
	"encoding/json"
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
	var out struct {
		Deleted int `json:"deleted"`
	}
	if err := c.POST(
		fmt.Sprintf("/api/platform/connectors/%s/endpoints/%s/purge", connectorID, endpointID),
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

// EndpointTest fires a single one-off request against an endpoint via
// POST /api/platform/connectors/{connectorID}/endpoints/{endpointID}/test,
// returning the raw JSON result without landing any data.
func (c *Client) EndpointTest(connectorID, endpointID string) (json.RawMessage, error) {
	var out json.RawMessage
	if err := c.POST(
		fmt.Sprintf("/api/platform/connectors/%s/endpoints/%s/test", connectorID, endpointID),
		map[string]any{},
		&out,
	); err != nil {
		return nil, err
	}
	return out, nil
}
