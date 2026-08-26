package client

import "fmt"

// SecretMeta is the metadata returned by the secrets API (value is never exposed).
type SecretMeta struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
	CreatedAt   string `json:"createdAt,omitempty"`
	CreatedBy   *int64 `json:"createdBy,omitempty"`
	UpdatedAt   string `json:"updatedAt,omitempty"`
	UpdatedBy   *int64 `json:"updatedBy,omitempty"`
	RotatedAt   string `json:"rotatedAt,omitempty"`
}

// SecretCreate creates a new secret via POST /api/platform/secrets; the value
// is stored write-only and never echoed back by later reads.
func (c *Client) SecretCreate(name, value, description string) error {
	return c.POST("/api/platform/secrets", map[string]string{
		"name": name, "value": value, "description": description,
	}, nil)
}

// SecretUpdate rotates an existing secret's value/description via
// PUT /api/platform/secrets/{name}.
func (c *Client) SecretUpdate(name, value, description string) error {
	return c.PUT(fmt.Sprintf("/api/platform/secrets/%s", name),
		map[string]string{"value": value, "description": description}, nil)
}

// SecretList lists secret metadata (never values) via GET /api/platform/secrets.
func (c *Client) SecretList() ([]SecretMeta, error) {
	var out []SecretMeta
	if err := c.GET("/api/platform/secrets", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// SecretDescribe fetches one secret's metadata via
// GET /api/platform/secrets/{name}.
func (c *Client) SecretDescribe(name string) (*SecretMeta, error) {
	var out SecretMeta
	if err := c.GET(fmt.Sprintf("/api/platform/secrets/%s", name), &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SecretDelete removes a secret via DELETE /api/platform/secrets/{name}.
func (c *Client) SecretDelete(name string) error {
	return c.DELETE(fmt.Sprintf("/api/platform/secrets/%s", name))
}
