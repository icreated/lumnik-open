package client

import "encoding/json"

// ValidationError is one manifest validation finding: the manifest path it
// applies to, its severity, a message, and suggested fixes.
type ValidationError struct {
	Path            string   `json:"path"`
	Severity        string   `json:"severity"`
	Message         string   `json:"message"`
	Recommendations []string `json:"recommendations"`
}

// ValidationResult groups a manifest validation's blocking errors and
// non-blocking warnings.
type ValidationResult struct {
	Errors   []ValidationError `json:"errors"`
	Warnings []ValidationError `json:"warnings"`
}

// ValidateManifest validates a raw YAML manifest against the hub's schema via
// POST /api/platform/validator/validate, without applying it.
func (c *Client) ValidateManifest(yaml []byte) (*ValidationResult, error) {
	data, err := c.doRaw("POST", "/api/platform/validator/validate", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	var vr ValidationResult
	if err := json.Unmarshal(data, &vr); err != nil {
		return nil, err
	}
	return &vr, nil
}
