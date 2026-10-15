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

// AppList returns the scope-bound registry (GET /api/apps, lm_user) as the hub sent it: the raw JSON, so
// a field this lm does not know yet still reaches -o json / -o yaml.
func (c *Client) AppList() (json.RawMessage, error) {
	var raw json.RawMessage
	err := c.GET("/api/apps", &raw)
	return raw, err
}

// AppDelete removes one declared app for good.
func (c *Client) AppDelete(name string) error {
	return c.DELETE("/api/apps/" + url.PathEscape(name))
}
