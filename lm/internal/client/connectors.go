package client

import (
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/types"
)

// ConnectorList lists every connector in the tenant via GET /api/platform/connectors.
func (c *Client) ConnectorList() ([]types.Connector, error) {
	var out []types.Connector
	if err := c.GET("/api/platform/connectors", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ConnectorGet fetches one connector by id via GET /api/platform/connectors/{id}.
func (c *Client) ConnectorGet(id string) (*types.Connector, error) {
	var out types.Connector
	if err := c.GET("/api/platform/connectors/"+id, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ConnectorCreate creates a connector from the FLAT admin payload — ConnectorCreateRequest is
// (tenantId, name, typeId, schedule, window, enabled, config) — via POST /api/platform/connectors, not
// from a manifest, whatever the name suggests.
// A kind: Connector manifest goes to ApplyManifest instead.
func (c *Client) ConnectorCreate(payload map[string]any) (*types.Connector, error) {
	var out types.Connector
	if err := c.POST("/api/platform/connectors", payload, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ConnectorUpdate updates an existing connector via PUT /api/platform/connectors/{id}.
func (c *Client) ConnectorUpdate(id string, payload map[string]any) (*types.Connector, error) {
	var out types.Connector
	if err := c.PUT("/api/platform/connectors/"+id, payload, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ConnectorDelete removes a connector, and its dynamic tables, via
// DELETE /api/platform/connectors/{id}.
func (c *Client) ConnectorDelete(id string) error {
	return c.DELETE("/api/platform/connectors/" + id)
}

// ConnectorDeleteDryRun asks the same door with ?dryRun=true: the hub runs the same refusals and
// answers the tables the delete would drop or empty, with the rows that would go, and the
// credential that would go with them, deleting none.
func (c *Client) ConnectorDeleteDryRun(id string) (types.DeleteTakes, error) {
	var out types.DeleteTakes
	err := c.do("DELETE", "/api/platform/connectors/"+id+"?dryRun=true", nil, &out)
	return out, err
}

// ConnectorRun triggers an ingestion run for a connector via
// POST /api/platform/connectors/{id}/run.
func (c *Client) ConnectorRun(id string) (map[string]any, error) {
	var out map[string]any
	if err := c.POST("/api/platform/connectors/"+id+"/run", map[string]any{}, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ConnectorPause suspends a connector's schedule via
// POST /api/platform/connectors/{id}/pause.
func (c *Client) ConnectorPause(id string) error {
	return c.POST("/api/platform/connectors/"+id+"/pause", map[string]any{}, nil)
}

// ConnectorResume re-arms a connector's schedule via
// POST /api/platform/connectors/{id}/resume.
func (c *Client) ConnectorResume(id string) error {
	return c.POST("/api/platform/connectors/"+id+"/resume", map[string]any{}, nil)
}

// ConnectorGetTags fetches a connector's tags via
// GET /api/platform/connectors/{id}/tags.
func (c *Client) ConnectorGetTags(id string) ([]string, error) {
	var out []string
	if err := c.GET("/api/platform/connectors/"+id+"/tags", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ConnectorSetTags replaces a connector's tags via
// PUT /api/platform/connectors/{id}/tags.
func (c *Client) ConnectorSetTags(id string, tags []string) ([]string, error) {
	var out []string
	if err := c.PUT("/api/platform/connectors/"+id+"/tags", tags, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ConnectorRetag re-applies a connector's current tags to its already-indexed
// RAG chunks (fixing chunks indexed before a tag change) via
// POST /api/platform/connectors/{id}/retag.
func (c *Client) ConnectorRetag(id string) (map[string]any, error) {
	var out map[string]any
	if err := c.POST("/api/platform/connectors/"+id+"/retag", map[string]any{}, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// SplitScopes parses the comma-separated form both the CLI (`scope set NAME a,b`) and the
// TUI's scope prompt take: trimmed, empties dropped. One parser for the value
// ConnectorSetTags sends — the two surfaces used to carry byte-identical copies.
func SplitScopes(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
