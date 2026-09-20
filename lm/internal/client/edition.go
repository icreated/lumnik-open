package client

import "time"

// editionTimeout bounds Edition() independent of c.http's general 30s budget: it's a cosmetic
// enrichment during `lm login` (and a suspended TUI re-login), not a real request the caller is
// waiting to succeed, so a slow or unreachable hub must not make either wait up to 30s for the
// plain fallback message. A var, not a const, so a test can shrink it.
var editionTimeout = 2 * time.Second

// Edition reports the connected hub's edition ("open" or "commercial"), read from
// GET /api/bff/v1/me. Informative only — callers decide what a failure means for them.
func (c *Client) Edition() (string, error) {
	var me struct {
		Edition string `json:"edition"`
	}
	if err := c.doOn(c.http, editionTimeout, "GET", "/api/bff/v1/me", nil, &me); err != nil {
		return "", err
	}
	return me.Edition, nil
}
