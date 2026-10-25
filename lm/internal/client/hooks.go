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

// HookGet fetches one hook by id via GET /api/platform/hooks/{id}. This is a different,
// richer shape than HookList's (HookResource.summarize, not HookAdminResource.toDto) — see
// types.HookDetail.
func (c *Client) HookGet(id string) (*types.HookDetail, error) {
	var out types.HookDetail
	if err := c.GET("/api/platform/hooks/"+id, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// HookScripts lists the hub's hooks directory via GET /api/platform/hooks/scripts (#1402).
func (c *Client) HookScripts() (*types.HookScripts, error) {
	var out types.HookScripts
	if err := c.GET("/api/platform/hooks/scripts", &out); err != nil {
		return nil, err
	}
	return &out, nil
}
