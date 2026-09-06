package client

import (
	"encoding/json"
	"net/url"
)

// EntityApply sends a raw YAML entity manifest to the hub.
// Content-Type: application/yaml. Returns raw JSON response.
func (c *Client) EntityApply(yaml []byte) (json.RawMessage, error) {
	data, err := c.doRaw("POST", "/api/entities/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}

// EntityDelete removes an entity manifest via DELETE /api/entities/{name}: the hub drops the
// entity's view, deletes its manifest row, and rediscovers its scopes so the RAG card drops it.
func (c *Client) EntityDelete(name string) error {
	return c.DELETE("/api/entities/" + url.PathEscape(name))
}
