package client

import "github.com/icreated/lumnik-open/lm/internal/types"

// MigrationStatus lists the Flyway migration status of every schema via
// GET /api/platform/migrations.
func (c *Client) MigrationStatus() ([]types.MigrationStatus, error) {
	var out []types.MigrationStatus
	if err := c.GET("/api/platform/migrations", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// MigrationApply runs the pending migrations for one schema via
// POST /api/platform/migrations/{schema}/apply.
func (c *Client) MigrationApply(schema string) error {
	return c.POST("/api/platform/migrations/"+schema+"/apply", map[string]any{}, nil)
}
