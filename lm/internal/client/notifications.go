package client

import "fmt"

// NotificationRule routes matching events to an email recipient — the third consumer of
// the spine, rule-driven.
type NotificationRule struct {
	ID           int64  `json:"id"`
	EventPattern string `json:"eventPattern"`
	EntityFilter string `json:"entityFilter"`
	Recipient    string `json:"recipient"`
	Template     string `json:"template"`
	Enabled      bool   `json:"enabled"`
}

type NotificationRuleCreate struct {
	EventPattern string `json:"eventPattern"`
	EntityFilter string `json:"entityFilter,omitempty"`
	Recipient    string `json:"recipient"`
	Template     string `json:"template,omitempty"`
}

func (c *Client) NotificationRuleCreate(in NotificationRuleCreate) (*NotificationRule, error) {
	var out NotificationRule
	if err := c.POST("/api/notifications", in, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) NotificationRuleList() ([]NotificationRule, error) {
	var out []NotificationRule
	if err := c.GET("/api/notifications", &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) NotificationRuleDelete(id int64) error {
	return c.DELETE(fmt.Sprintf("/api/notifications/%d", id))
}
