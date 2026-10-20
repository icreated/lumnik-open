package client

import (
	"fmt"
	"time"
)

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
	HasSecret  bool     `json:"hasSecret"`
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

// IntegrationDelivery is one attempt in a target's own history. The hub sends no succeeded flag
// on this door; a delivery succeeded when it got a 2xx, the hub's own rule.
type IntegrationDelivery struct {
	ID          int64     `json:"id"`
	EventType   string    `json:"eventType"`
	HTTPStatus  *int      `json:"httpStatus"`
	Error       string    `json:"error"`
	DurationMs  int       `json:"durationMs"`
	AttemptedAt time.Time `json:"attemptedAt"`
}

func (d IntegrationDelivery) Succeeded() bool {
	return d.HTTPStatus != nil && *d.HTTPStatus >= 200 && *d.HTTPStatus < 300
}

// IntegrationDeliveries returns a target's most recent attempts, newest first.
func (c *Client) IntegrationDeliveries(id int64, limit int) ([]IntegrationDelivery, error) {
	var out []IntegrationDelivery
	if err := c.GET(fmt.Sprintf("/api/platform/integrations/%d/deliveries?limit=%d", id, limit), &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) IntegrationTest(id int64, eventType string) (*IntegrationTestResult, error) {
	var out IntegrationTestResult
	body := map[string]string{"eventType": eventType}
	if err := c.POST(fmt.Sprintf("/api/platform/integrations/%d/test", id), body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
