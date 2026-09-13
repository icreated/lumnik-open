package client

import "encoding/json"

// postYamlRoute posts a JSON body to a discovery route that answers YAML.
//
// It exists to state the rule ONCE, structurally: these routes are @Produces(TEXT_PLAIN) and
// answer text/plain YAML on success, JSON on error — so the request must carry NO Accept header.
// Going through the ordinary JSON POST helper sets Accept: application/json, which the route
// refuses with a bodiless 406 before its method ever runs. That is exactly how `lm source rest
// probe` stopped at "API error 406 (no details from the server)" on every invocation ever made,
// while its neighbour worked: the rule was written as a comment on one of the two.
func (c *Client) postYamlRoute(path string, payload map[string]any) ([]byte, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	return c.doRaw("POST", path, "application/json", "", body)
}

// RestFromOpenApi calls the server to generate a RestConnector manifest YAML
// from a remote OpenAPI spec URL.
func (c *Client) RestFromOpenApi(url, name string, tags []string) ([]byte, error) {
	return c.postYamlRoute("/api/platform/sources/rest/from-openapi", map[string]any{
		"url":  url,
		"name": name,
		"tags": tags,
	})
}

// RestProbe calls the server to sample-fetch one path of an existing connector and return an
// endpoint manifest fragment (v1: deterministic, no LLM call). The connector carries the
// connection and the credential, so nothing secret rides in this body.
func (c *Client) RestProbe(connectorID, endpointPath string) ([]byte, error) {
	return c.postYamlRoute("/api/platform/sources/rest/probe", map[string]any{
		"connectorId":  connectorID,
		"endpointPath": endpointPath,
	})
}
