package client

import (
	"fmt"
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
func (c *Client) ObservationList(limit int) ([]Observation, error) {
	var out []Observation
	if err := c.GET(fmt.Sprintf("/api/platform/workflows/observations?limit=%d", limit), &out); err != nil {
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
