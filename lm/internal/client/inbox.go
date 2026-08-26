package client

import "fmt"

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

// InboxList returns the tenant's most recent decision.* events, newest first.
// By default the fold hides acked decisions; all=true returns everything with the acked flag.
func (c *Client) InboxList(limit int, all bool) ([]InboxEntry, error) {
	path := fmt.Sprintf("/api/inbox?limit=%d", limit)
	if all {
		path += "&all=true"
	}
	var out []InboxEntry
	if err := c.GET(path, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// InboxAck disposes of a decision: emits annotation.decision.acked (idempotent server-side).
func (c *Client) InboxAck(id int64) error {
	return c.POST(fmt.Sprintf("/api/inbox/%d/ack", id), map[string]any{}, nil)
}
