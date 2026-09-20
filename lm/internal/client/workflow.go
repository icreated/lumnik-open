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

// DeleteWorkflow removes one declared workflow for good — the delete gesture #487 gave
// kind:Process, missing here (#507).
func (c *Client) DeleteWorkflow(name string) error {
	return c.DELETE("/api/workflows/" + url.PathEscape(name))
}

// WorkflowEnabled is the hub's answer to a SetWorkflowEnabled call.
type WorkflowEnabled struct {
	Name    string `json:"name"`
	Enabled bool   `json:"enabled"`
}

// SetWorkflowEnabled turns one workflow on or off — the reversible half of DeleteWorkflow: the
// manifest stays readable and the hub stops treating it as a live lifecycle.
func (c *Client) SetWorkflowEnabled(name string, enabled bool) (*WorkflowEnabled, error) {
	var out WorkflowEnabled
	if err := c.PUT("/api/workflows/"+url.PathEscape(name)+"/enabled",
		map[string]any{"enabled": enabled}, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
