package client

import (
	"fmt"
	"time"
)

// MailDelivery is one e-mail the hub was asked to send for a notification rule: who, which event, how it
// stands. Status is the hub's word (sent, retrying, given up: send, given up: render). Error is the kind of
// failure, never a message; the mail itself is not part of the row.
type MailDelivery struct {
	ID        int64     `json:"id"`
	RuleID    int64     `json:"ruleId"`
	Recipient string    `json:"recipient"`
	EventType string    `json:"eventType"`
	Status    string    `json:"status"`
	Attempts  int       `json:"attempts"`
	Error     string    `json:"error"`
	At        time.Time `json:"at"`
}

// MailDeliveryList returns the tenant's mail deliveries, given-up ones first — the order is the server's.
func (c *Client) MailDeliveryList(limit int) ([]MailDelivery, error) {
	var out []MailDelivery
	if err := c.GET(fmt.Sprintf("/api/platform/notification-deliveries?limit=%d", limit), &out); err != nil {
		return nil, err
	}
	return out, nil
}

// MailDeliveryReplay sends one given-up mail again and returns where the delivery stands afterwards.
func (c *Client) MailDeliveryReplay(id int64) (string, error) {
	var out struct {
		Status string `json:"status"`
	}
	if err := c.POST(fmt.Sprintf("/api/platform/notification-deliveries/%d/replay", id), map[string]any{}, &out); err != nil {
		return "", err
	}
	return out.Status, nil
}
