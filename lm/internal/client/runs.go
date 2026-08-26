package client

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// RunList fetches the global recent runs.
func (c *Client) RunList(limit int) ([]types.Run, error) {
	var out []types.Run
	if err := c.GET(fmt.Sprintf("/api/platform/runs?limit=%d", limit), &out); err != nil {
		return nil, err
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
