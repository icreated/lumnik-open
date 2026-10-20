package client

import (
	"errors"
	"net/url"
)

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
func (c *Client) SecretCreate(name, value string, description *string) error {
	body := map[string]any{"name": name, "value": value}
	if description != nil {
		body["description"] = *description
	}
	return c.POST("/api/platform/secrets", body, nil)
}

// SecretUpdate rotates an existing secret's value via PUT /api/platform/secrets/{name}. A nil
// description keeps the stored one: the hub keeps it only when the field is absent (#1249).
func (c *Client) SecretUpdate(name, value string, description *string) error {
	body := map[string]any{"value": value}
	if description != nil {
		body["description"] = *description
	}
	return c.PUT("/api/platform/secrets/"+url.PathEscape(name), body, nil)
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
	if err := c.GET("/api/platform/secrets/"+url.PathEscape(name), &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SecretDelete removes a secret via DELETE /api/platform/secrets/{name}.
func (c *Client) SecretDelete(name string) error {
	return c.DELETE("/api/platform/secrets/" + url.PathEscape(name))
}

// SecretSet creates the secret, or rotates it when the name is taken (the hub's 409) — what
// lm secret set and :secrets both mean by "store". created says which happened; a nil
// description keeps a rotated secret's label.
func (c *Client) SecretSet(name, value string, description *string) (created bool, err error) {
	err = c.SecretCreate(name, value, description)
	var apiErr *APIError
	if errors.As(err, &apiErr) && apiErr.Status == 409 {
		return false, c.SecretUpdate(name, value, description)
	}
	return err == nil, err
}
