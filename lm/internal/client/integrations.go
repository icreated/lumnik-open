package client

import "fmt"

// Integration is a registered webhook target: the road from the event spine to the tool
// that pings phones. The secret is write-only — the server never returns it.
type Integration struct {
	ID         int64    `json:"id"`
	Name       string   `json:"name"`
	TargetURL  string   `json:"targetUrl"`
	Platform   string   `json:"platform"`
	AuthMode   string   `json:"authMode"`
	EventTypes []string `json:"eventTypes"`
	Enabled    bool     `json:"enabled"`
}

type IntegrationCreate struct {
	Name       string   `json:"name"`
	TargetURL  string   `json:"targetUrl"`
	Platform   string   `json:"platform,omitempty"`
	AuthMode   string   `json:"authMode,omitempty"`
	EventTypes []string `json:"eventTypes"`
	Secret     string   `json:"secret,omitempty"`
	Enabled    *bool    `json:"enabled,omitempty"`
}

type IntegrationTestResult struct {
	OK         bool   `json:"ok"`
	HTTPStatus *int   `json:"httpStatus"`
	Error      string `json:"error"`
	DurationMs int    `json:"durationMs"`
}

func (c *Client) IntegrationCreate(in IntegrationCreate) (*Integration, error) {
	var out Integration
	if err := c.POST("/api/platform/integrations", in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) IntegrationList() ([]Integration, error) {
	var out []Integration
	if err := c.GET("/api/platform/integrations", &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) IntegrationGet(id int64) (*Integration, error) {
	var out Integration
	if err := c.GET(fmt.Sprintf("/api/platform/integrations/%d", id), &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) IntegrationDelete(id int64) error {
	return c.DELETE(fmt.Sprintf("/api/platform/integrations/%d", id))
}

func (c *Client) IntegrationTest(id int64, eventType string) (*IntegrationTestResult, error) {
	var out IntegrationTestResult
	body := map[string]string{"eventType": eventType}
	if err := c.POST(fmt.Sprintf("/api/platform/integrations/%d/test", id), body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
