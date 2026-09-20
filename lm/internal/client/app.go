package client

import (
	"encoding/json"
	"net/url"
)

// AppApply sends a raw YAML kind:App manifest to the hub.
// Content-Type: application/yaml. Returns the raw JSON response.
func (c *Client) AppApply(yaml []byte) (json.RawMessage, error) {
	data, err := c.doRaw("POST", "/api/apps/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}

// AppDelete removes one declared app for good.
func (c *Client) AppDelete(name string) error {
	return c.DELETE("/api/apps/" + url.PathEscape(name))
}
