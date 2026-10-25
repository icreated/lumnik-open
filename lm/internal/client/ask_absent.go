package client

import (
	"errors"
	"strings"
)

// askAbsent turns a 404 from an Ask door (chat, analytic ask, semantic search, corpora, scopes) into
// a sentence naming why Ask is not there (#1581). An Open hub has no route for it and answers with
// a framework phrase; a Pro hub with the AI switched off answers a bare 404 (AiDoorFilter). Any
// other failure — a 404 with the hub's own reason included — is returned as the hub gave it.
func (c *Client) askAbsent(err error) error {
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 404 {
		return err
	}
	edition, editionErr := c.Edition()
	switch {
	case editionErr != nil:
		return err
	case edition == "open":
		return errors.New("this hub's build has no Ask: lumnik Open has no chat, no lm ask and no semantic search " +
			"(lm module list shows no rag module); Ask ships in lumnik Pro")
	case strings.TrimSpace(apiErr.Body) == "":
		return errors.New("this hub has Ask switched off (LUMNIK_AI_ENABLED=false): lm module list shows rag INACTIVE; " +
			"the operator turns it back on")
	default:
		return err
	}
}
