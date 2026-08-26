package client

import "net/url"

// ApplyWorkflow sends a kind:Workflow manifest for create-or-update.
func (c *Client) ApplyWorkflow(body []byte) ([]byte, error) {
	return c.doRaw("POST", "/api/workflows/apply", "application/yaml", "application/json", body)
}

// ListWorkflows returns the tenant's declared kind:Workflow manifests, as the hub's raw
// JSON array — the cli formats it for the table.
func (c *Client) ListWorkflows() ([]byte, error) {
	return c.doRaw("GET", "/api/workflows", "", "application/json", nil)
}

// GetWorkflowManifest fetches one declared workflow's manifest YAML, verbatim. The name is
// URL-escaped: it rides straight into the path segment and is caller-supplied.
func (c *Client) GetWorkflowManifest(name string) ([]byte, error) {
	// JSON fallback in Accept: error bodies (403/404) are JSON — a yaml-only Accept could 406 them.
	return c.doRaw("GET", "/api/workflows/"+url.PathEscape(name)+"/manifest", "",
		"application/yaml, application/json;q=0.9", nil)
}
