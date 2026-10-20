package client

import (
	"encoding/json"
	"net/url"
)

// ProcessInfo is one declared kind:Process, as listed by the hub.
type ProcessInfo struct {
	Name    string `json:"name"`
	Table   string `json:"table"`
	Emit    string `json:"emit"`
	Enabled bool   `json:"enabled"`
}

// ProcessApply sends a kind:Process manifest for create-or-update.
func (c *Client) ProcessApply(yaml []byte) (json.RawMessage, error) {
	data, err := c.doRaw("POST", "/api/processes/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}

// ProcessManifest returns the stored manifest yaml for one process — the edit round-trip.
func (c *Client) ProcessManifest(name string) ([]byte, error) {
	// JSON fallback in Accept: error bodies (401/404) are JSON — a yaml-only Accept could 406 them.
	return c.doRaw("GET", "/api/processes/"+url.PathEscape(name)+"/manifest", "",
		"application/yaml, application/json;q=0.9", nil)
}

// ProcessList returns the tenant's declared processes.
func (c *Client) ProcessList() ([]ProcessInfo, error) {
	var out []ProcessInfo
	if err := c.GET("/api/processes", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ProcessDelete removes one declared process for good.
func (c *Client) ProcessDelete(name string) error {
	return c.DELETE("/api/processes/" + url.PathEscape(name))
}

// ProcessSetEnabled turns one process on or off — the reversible half of ProcessDelete: the
// manifest stays readable and the hub stops counting it as a subscriber.
func (c *Client) ProcessSetEnabled(name string, enabled bool) (*ProcessEnabled, error) {
	var out ProcessEnabled
	if err := c.PUT("/api/processes/"+url.PathEscape(name)+"/enabled",
		map[string]any{"enabled": enabled}, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ProcessEnabled is the hub's answer to a ProcessSetEnabled call.
type ProcessEnabled struct {
	Name    string `json:"name"`
	Enabled bool   `json:"enabled"`
}
