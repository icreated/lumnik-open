package client

import "encoding/json"

// RestFromOpenApi calls the server to generate a RestConnector manifest YAML
// from a remote OpenAPI spec URL. The server returns text/plain YAML, so we
// read raw bytes instead of JSON-decoding.
func (c *Client) RestFromOpenApi(url, name string, tags []string) ([]byte, error) {
	payload, err := json.Marshal(map[string]any{
		"url":  url,
		"name": name,
		"tags": tags,
	})
	if err != nil {
		return nil, err
	}
	// No Accept header: the server answers text/plain YAML on success and JSON on error.
	return c.doRaw("POST", "/api/platform/sources/rest/from-openapi", "application/json", "", payload)
}

// RestProbe calls the server to sample-fetch an endpoint and return an
// LLM-augmented manifest draft (v1: deterministic, no LLM call).
func (c *Client) RestProbe(connectorID, endpointPath string) (json.RawMessage, error) {
	var out json.RawMessage
	if err := c.POST("/api/platform/sources/rest/probe", map[string]any{
		"connectorId":  connectorID,
		"endpointPath": endpointPath,
	}, &out); err != nil {
		return nil, err
	}
	return out, nil
}
