package client

import (
	"fmt"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// hookEnvelope matches the existing HookAdminResource shape returning a flat list of maps.
type hookEnvelope []map[string]any

// HookList lists every registered hook via GET /api/platform/admin/hooks.
func (c *Client) HookList() ([]types.Hook, error) {
	var raw hookEnvelope
	if err := c.GET("/api/platform/admin/hooks", &raw); err != nil {
		return nil, err
	}
	out := make([]types.Hook, 0, len(raw))
	for _, m := range raw {
		out = append(out, types.Hook{
			ID:     str(m["id"]),
			Type:   str(m["type"]),
			Entity: str(m["entity"]),
			Scope:  str(m["scope"]),
			Status: str(m["status"]),
		})
	}
	return out, nil
}

func str(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprintf("%v", v)
}

// HookGet fetches one hook by id via GET /api/platform/hooks/{id}.
func (c *Client) HookGet(id string) (*types.Hook, error) {
	var out types.Hook
	if err := c.GET("/api/platform/hooks/"+id, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// HookApply creates or updates a hook via PUT /api/platform/hooks/{id}, keyed by
// the payload's own "id" field.
func (c *Client) HookApply(payload map[string]any) error {
	id, _ := payload["id"].(string)
	if id == "" {
		return fmt.Errorf("hook payload missing 'id'")
	}
	return c.PUT("/api/platform/hooks/"+id, payload, nil)
}

// HookDelete removes a hook via DELETE /api/platform/hooks/{id}.
func (c *Client) HookDelete(id string) error {
	return c.DELETE("/api/platform/hooks/" + id)
}

// HookReload re-loads a hook's compiled script into the .kts runtime via
// POST /api/platform/hooks/{id}/reload.
func (c *Client) HookReload(id string) error {
	return c.POST("/api/platform/hooks/"+id+"/reload", nil, nil)
}
