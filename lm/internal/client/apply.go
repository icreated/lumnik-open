package client

import "encoding/json"

// ApplyManifest sends a raw YAML manifest to the server for create-or-update.
// The body is passed as-is with Content-Type: application/yaml.
// Returns the raw JSON response from the server.
func (c *Client) ApplyManifest(yaml []byte) (json.RawMessage, error) {
	data, err := c.doRaw("POST", "/api/platform/connectors/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}
