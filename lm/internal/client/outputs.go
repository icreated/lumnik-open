package client

import (
	"fmt"
	"time"
)

// Output is one thing the substrate sent out: which integration, which event, and whether it
// arrived. HTTPStatus is nil when the request never got a response at all.
type Output struct {
	ID            int64     `json:"id"`
	IntegrationID int64     `json:"integrationId"`
	Integration   string    `json:"integration"`
	EventType     string    `json:"eventType"`
	HTTPStatus    *int      `json:"httpStatus"`
	Error         string    `json:"error"`
	DurationMs    int       `json:"durationMs"`
	AttemptedAt   time.Time `json:"attemptedAt"`
	Succeeded     bool      `json:"succeeded"`
}

// OutputList returns the tenant's deliveries across every integration, failures first — the
// order is the server's, not something the caller re-derives.
func (c *Client) OutputList(limit int) ([]Output, error) {
	var out []Output
	if err := c.GET(fmt.Sprintf("/api/platform/deliveries?limit=%d", limit), &out); err != nil {
		return nil, err
	}
	return out, nil
}

// OutputReplay re-sends one delivery. The hub records a new attempt; the original stays.
func (c *Client) OutputReplay(id int64) error {
	return c.POST(fmt.Sprintf("/api/platform/deliveries/%d/replay", id), map[string]any{}, nil)
}
