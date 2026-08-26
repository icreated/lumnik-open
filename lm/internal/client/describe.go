package client

import "encoding/json"

// DescribeConnectorType fetches the JSON schema for a connector kind via
// GET /api/platform/validator/describe/{kind}.
func (c *Client) DescribeConnectorType(kind string) (json.RawMessage, error) {
	data, err := c.doRaw("GET", "/api/platform/validator/describe/"+kind, "", "application/json", nil)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}
