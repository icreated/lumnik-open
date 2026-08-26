package client

import "encoding/json"

// EntityApply sends a raw YAML entity manifest to the hub.
// Content-Type: application/yaml. Returns raw JSON response.
func (c *Client) EntityApply(yaml []byte) (json.RawMessage, error) {
	data, err := c.doRaw("POST", "/api/entities/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}
