package client

import (
	"fmt"
	"net/url"
	"strconv"
)

// InboxEntry is one decision waiting for the human — the fruit of a declared kind:Process rule.
type InboxEntry struct {
	ID         int64  `json:"id"`
	OccurredAt string `json:"occurredAt"`
	Type       string `json:"type"`
	Process    string `json:"process"`
	Message    string `json:"message"`
	Entity     string `json:"entity"`
	EntityKey  string `json:"entityKey"`
	Acked      bool   `json:"acked"`
}

// InboxQuery is what one inbox read asks for. All includes acked decisions; Process and Type,
// when set, narrow the hub's query (#1356) — Process is what a decision shows as its process.
type InboxQuery struct {
	Limit         int
	All           bool
	Process, Type string
}

// InboxList returns the tenant's most recent decision.* events, newest first.
// By default the fold hides acked decisions; All returns everything with the acked flag.
func (c *Client) InboxList(q InboxQuery) ([]InboxEntry, error) {
	v := url.Values{"limit": {strconv.Itoa(q.Limit)}}
	if q.All {
		v.Set("all", "true")
	}
	if q.Process != "" {
		v.Set("process", q.Process)
	}
	if q.Type != "" {
		v.Set("type", q.Type)
	}
	var out []InboxEntry
	if err := c.GET("/api/inbox?"+v.Encode(), &out); err != nil {
		return nil, err
	}
	return out, nil
}

// InboxAck disposes of a decision: emits annotation.decision.acked (idempotent server-side).
func (c *Client) InboxAck(id int64) error {
	return c.POST(fmt.Sprintf("/api/inbox/%d/ack", id), map[string]any{}, nil)
}
