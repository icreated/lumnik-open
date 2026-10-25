package client

import (
	"net/url"
	"strconv"
	"time"
)

// Observation is one watched document's standing state: what it is, since when WE have
// observed it there, and whether the dormancy sweep has already alerted on it.
type Observation struct {
	Workflow        string     `json:"workflow"`
	DocKey          string     `json:"docKey"`
	State           string     `json:"state"`
	Since           time.Time  `json:"since"`
	FirstObservedAt time.Time  `json:"firstObservedAt"`
	AlertedAt       *time.Time `json:"alertedAt"`
}

// ObservationList returns the tenant's workflow tape, stillest first — the order is the
// server's (ORDER BY since ASC), not something the caller re-derives.
// workflow, when set, narrows the hub's query to one workflow's documents (#1361).
func (c *Client) ObservationList(limit int, workflow string) ([]Observation, error) {
	v := url.Values{"limit": {strconv.Itoa(limit)}}
	if workflow != "" {
		v.Set("workflow", workflow)
	}
	var out []Observation
	if err := c.GET("/api/platform/workflows/observations?"+v.Encode(), &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ObservationStats is the tape aggregated per workflow: the sponsor's numbers.
// MedianDetectionSeconds is nil when nothing is dormant — absence, not zero.
type ObservationStatsRow struct {
	Workflow               string `json:"workflow"`
	Watched                int64  `json:"watched"`
	Dormant                int64  `json:"dormant"`
	MedianDetectionSeconds *int64 `json:"medianDetectionSeconds"`
}

func (c *Client) ObservationStats() ([]ObservationStatsRow, error) {
	var out []ObservationStatsRow
	if err := c.GET("/api/platform/workflows/observations/stats", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// Transition is one move of the workflow journal (#1368). EnteredFromAt is when the document
// entered From: its previous move, or its first observation for its first one — observation-
// relative, so a lower bound on the time spent in From.
type Transition struct {
	Workflow      string    `json:"workflow"`
	DocKey        string    `json:"docKey"`
	From          string    `json:"from"`
	To            string    `json:"to"`
	EnteredFromAt time.Time `json:"enteredFromAt"`
	ObservedAt    time.Time `json:"observedAt"`
}

// ObservationTransitions returns the journal, newest first; workflow and doc narrow the hub's query.
func (c *Client) ObservationTransitions(limit int, workflow, doc string) ([]Transition, error) {
	v := url.Values{"limit": {strconv.Itoa(limit)}}
	if workflow != "" {
		v.Set("workflow", workflow)
	}
	if doc != "" {
		v.Set("doc", doc)
	}
	var out []Transition
	if err := c.GET("/api/platform/workflows/observations/transitions?"+v.Encode(), &out); err != nil {
		return nil, err
	}
	return out, nil
}
