package cli

import (
	"fmt"
	"strings"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// The list views abbreviate ids to 8 characters through shortID — and unlike truncate, which
// appends "…", shortID leaves no mark that anything was cut. So an operator copies what the ID
// column showed and hands it back to `get`/`discard`/`replayed`, where the hub binds
// @PathParam("id") UUID and cannot parse it. What the tool displays has to be what it accepts.
//
// An exact id always wins, so a full UUID never pays for a lookup that could go stale.
func resolveIDPrefix(kind, listCmd string, ids []string, arg string) (string, error) {
	var hits []string
	for _, id := range ids {
		if id == arg {
			return id, nil
		}
		if strings.HasPrefix(id, arg) {
			hits = append(hits, id)
		}
	}
	switch len(hits) {
	case 1:
		return hits[0], nil
	case 0:
		return "", fmt.Errorf("no %s matches %q — `%s -o yaml` shows the ids in full", kind, arg, listCmd)
	default:
		return "", fmt.Errorf("%q matches %d %ss — use more characters", arg, len(hits), kind)
	}
}

// resolveDlqID turns an abbreviated dead-letter id back into the one the API needs.
//
// The filters matter: an operator copies from `lm dlq list --run X`, so resolution has to run
// against the list they actually saw. Against the unfiltered set the same 8 characters could be
// ambiguous — or worse, unique but a DIFFERENT entry.
func resolveDlqID(c *client.Client, connectorID, runID, arg string) (string, error) {
	if len(arg) == uuidLen {
		return arg, nil
	}
	items, err := c.DlqList(connectorID, runID, client.DlqPageCap)
	if err != nil {
		return "", err
	}
	ids := make([]string, 0, len(items))
	for _, d := range items {
		ids = append(ids, d.ID)
	}
	return resolveIDPrefix("dead letter", "lm dlq list", ids, arg)
}

// resolveRunID turns an abbreviated run id back into the one the API needs.
func resolveRunID(c *client.Client, arg string) (string, error) {
	if len(arg) == uuidLen {
		return arg, nil
	}
	runs, err := c.RunList("", "", client.RunsPageCap)
	if err != nil {
		return "", err
	}
	ids := make([]string, 0, len(runs))
	for _, r := range runs {
		ids = append(ids, r.ID)
	}
	return resolveIDPrefix("run", "lm run list", ids, arg)
}

const (
	uuidLen = 36
)
