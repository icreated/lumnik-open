package client

import "github.com/icreated/lumnik-open/lm/internal/types"

type modulesEnvelope struct {
	Modules []moduleManifest `json:"modules"`
}

type moduleManifest struct {
	ID          string `json:"id"`
	Version     string `json:"version"`
	DisplayName string `json:"displayName"`
	Active      bool   `json:"active"`
	// InactiveReason is absent from the wire while the module is active.
	InactiveReason string `json:"inactiveReason"`
}

// ModuleList lists every installed hub module via GET /api/platform/modules,
// deriving each module's ACTIVE/INACTIVE status from the server's active flag.
func (c *Client) ModuleList() ([]types.Module, error) {
	var env modulesEnvelope
	if err := c.GET("/api/platform/modules", &env); err != nil {
		return nil, err
	}
	out := make([]types.Module, 0, len(env.Modules))
	for _, m := range env.Modules {
		status := "INACTIVE"
		if m.Active {
			status = "ACTIVE"
		}
		out = append(out, types.Module{
			Name:    m.ID,
			Version: m.Version,
			Schema:  m.DisplayName,
			Status:  status,
			Reason:  m.InactiveReason,
		})
	}
	return out, nil
}
