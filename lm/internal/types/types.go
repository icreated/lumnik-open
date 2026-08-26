package types

import "time"

// Module is one installed hub module: its identifying name, version, owning
// schema, activation status, and install timestamp.
type Module struct {
	Name        string    `json:"name"`
	Version     string    `json:"version"`
	Schema      string    `json:"schema"`
	Status      string    `json:"status"`
	InstalledAt time.Time `json:"installedAt"`
}

// Connector is a configured data-ingestion connector: its type, schedule,
// enabled state, freeform manifest config, tags, and last-known run status.
type Connector struct {
	ID        string         `json:"id" yaml:"id"`
	TenantID  int64          `json:"tenantId" yaml:"tenantId"`
	Name      string         `json:"name" yaml:"name"`
	TypeID    string         `json:"typeId" yaml:"typeId"`
	Schedule  string         `json:"schedule,omitempty" yaml:"schedule,omitempty"`
	Enabled   bool           `json:"enabled" yaml:"enabled"`
	Config    map[string]any `json:"config" yaml:"config"`
	Tags      []string       `json:"tags" yaml:"tags"`
	Status    string         `json:"status,omitempty" yaml:"status,omitempty"`
	LastRunAt *time.Time     `json:"lastRunAt,omitempty" yaml:"lastRunAt,omitempty"`
}

// Run is one connector ingestion run: its lifecycle status, timing, and the
// conservation-invariant record counts. recordsIn = recordsOut + recordsSkipped,
// with deadLetterCount a subset of recordsSkipped (the DLQ holds the skips that
// were errors; benign skips — skip-empty-rows / filter-where — are counted here
// too but never reach the DLQ). A hub older than this field's addition omits
// recordsSkipped from the JSON; it decodes as 0.
//
// CellErrors is orthogonal to the conservation identity above: it counts CELLS a
// mapping hook (parse-date, parse-number, parse-email, parse-phone, lookup) could not
// parse and nulled, letting the row through unchanged. The row WAS written — never
// add or compare CellErrors against the records_* counts. A hub older than this
// field's addition omits cellErrors from the JSON; it decodes as 0.
type Run struct {
	ID              string     `json:"id"`
	ConnectorID     string     `json:"connectorId"`
	ConnectorName   string     `json:"connectorName"` // absent on hubs older than the ledger-names change
	Status          string     `json:"status"`
	StartedAt       time.Time  `json:"startedAt"`
	EndedAt         *time.Time `json:"endedAt,omitempty"`
	RecordsIn       int64      `json:"recordsIn"`
	RecordsOut      int64      `json:"recordsOut"`
	RecordsSkipped  int64      `json:"recordsSkipped"`
	CellErrors      int64      `json:"cellErrors"`
	TriggeredBy     string     `json:"triggeredBy"`
	ChunkCount      int        `json:"chunkCount"`
	ErrorMessage    string     `json:"errorMessage"`
	DeadLetterCount int64      `json:"deadLetterCount"`
}

// ConnectorLabel is what a human should read in a CONNECTOR column: the
// connector's name when the hub sent it, else the shortened id (older hubs).
// One answer shared by the CLI table and the TUI panel, so they cannot drift.
func (r Run) ConnectorLabel() string {
	if r.ConnectorName != "" {
		return r.ConnectorName
	}
	if len(r.ConnectorID) > 8 {
		return r.ConnectorID[:8]
	}
	return r.ConnectorID
}

// DeadLetter is one record the mapping pipeline could not process: why it was
// skipped or errored, how many attempts were made, its resolution state, and
// (when captured) the offending payload.
type DeadLetter struct {
	ID           string    `json:"id"`
	ConnectorID  string    `json:"connectorId"`
	RunID        string    `json:"runId"`
	SkipReason   string    `json:"skipReason"`
	ErrorMessage string    `json:"errorMessage"`
	Attempts     int       `json:"attempts"`
	Resolution   string    `json:"resolution,omitempty"`
	CapturedAt   time.Time `json:"capturedAt"`
	Payload      string    `json:"payload,omitempty"`
	SkipDetail   string    `json:"skipDetail,omitempty"`
}

// Hook is one registered lifecycle hook: its type, the entity it targets, its
// scope, and its load status.
type Hook struct {
	ID     string `json:"id"`
	Type   string `json:"type"`
	Entity string `json:"entity"`
	Scope  string `json:"scope"`
	Status string `json:"status"`
}

// MigrationStatus reports one schema's Flyway migration state: current version,
// pending count, and when the last migration was applied.
type MigrationStatus struct {
	Schema         string    `json:"schema"`
	CurrentVersion string    `json:"currentVersion"`
	Pending        int       `json:"pending"`
	LastApplied    time.Time `json:"lastApplied"`
}

// Endpoint is one connector endpoint: its HTTP method/path, schedule, enabled
// state, and last-run outcome.
type Endpoint struct {
	ID            string     `json:"id"`
	ConnectorID   string     `json:"connectorId"`
	Name          string     `json:"name"`
	Method        string     `json:"method"`
	Path          string     `json:"path"`
	Schedule      string     `json:"schedule,omitempty"`
	Enabled       bool       `json:"enabled"`
	LastRunAt     *time.Time `json:"lastRunAt,omitempty"`
	LastRunStatus string     `json:"lastRunStatus,omitempty"`
}

// CursorState is an endpoint's persisted read position, used to resume
// incremental ingestion from where the last run left off.
type CursorState struct {
	EndpointID   string     `json:"endpointId"`
	Mode         string     `json:"mode"`
	CursorValue  string     `json:"cursorValue,omitempty"`
	LastSyncedAt *time.Time `json:"lastSyncedAt,omitempty"`
}
