package types

import "time"

// Module is one installed hub module: its identifying name, version, owning
// schema, activation status, and install timestamp.
type Module struct {
	Name    string `json:"name"`
	Version string `json:"version"`
	Schema  string `json:"schema"`
	Status  string `json:"status"`
	// Reason says why a module is INACTIVE (empty when it is active): without it the operator
	// sees INACTIVE and does not know what to fix.
	Reason      string    `json:"reason,omitempty"`
	InstalledAt time.Time `json:"installedAt"`
}

// Connector is a configured data-ingestion connector: its type, schedule,
// enabled state, freeform manifest config, tags, and last-known run status.
type Connector struct {
	ID       string `json:"id" yaml:"id"`
	TenantID int64  `json:"tenantId" yaml:"tenantId"`
	Name     string `json:"name" yaml:"name"`
	TypeID   string `json:"typeId" yaml:"typeId"`
	Schedule string `json:"schedule,omitempty" yaml:"schedule,omitempty"`
	// Window fences the SCHEDULED runs to a UTC time slot, HH:MM-HH:MM, beside Schedule's cron.
	Window    string         `json:"window,omitempty" yaml:"window,omitempty"`
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
	EndpointID      string     `json:"endpointId"`    // empty for a run with no endpoint, and on older hubs
	EndpointName    string     `json:"endpointName"`
	Status          string     `json:"status"`
	StartedAt       *time.Time `json:"startedAt,omitempty"` // nil while the run is still queued
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

// ConnectorLabel is what a human should read in a CONNECTOR column — see nameOrShortID.
func (r Run) ConnectorLabel() string { return nameOrShortID(r.ConnectorName, r.ConnectorID) }

// EndpointLabel is what a human should read in an ENDPOINT column — the same rule one level
// down. It is what tells the sibling rows of one fan-out apart: a connector writes one run row
// per endpoint, so a two-table jdbc connector logs two rows that differ only in their counters
// until this cell names the table each one read. Empty for a run with no endpoint at all — a row
// predating endpoints, or one whose endpoint was hard-deleted — because a placeholder there
// would read as a name.
func (r Run) EndpointLabel() string { return nameOrShortID(r.EndpointName, r.EndpointID) }

// ConnectorLabel — the same rule, for the dead-letter queue.
func (d DeadLetter) ConnectorLabel() string { return nameOrShortID(d.ConnectorName, d.ConnectorID) }

// ConnectorNameAndID is the detail-view form: "name (full id)" — the name for the
// reader, the id for the script — or just the id when the hub sent no name.
func (d DeadLetter) ConnectorNameAndID() string {
	if d.ConnectorName == "" {
		return d.ConnectorID
	}
	return d.ConnectorName + " (" + d.ConnectorID + ")"
}

// nameOrShortID: the name when the hub sent it, else the shortened id (older hubs,
// or a row the hub could not resolve), else empty. One answer shared by the CLI
// tables and the TUI panels, so they cannot drift.
func nameOrShortID(name, id string) string {
	if name != "" {
		return name
	}
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

// DeadLetter is one record the mapping pipeline could not process: why it was
// skipped or errored, how many attempts were made, its resolution state, and
// (when captured) the offending payload.
type DeadLetter struct {
	ID            string    `json:"id"`
	ConnectorID   string    `json:"connectorId"`
	ConnectorName string    `json:"connectorName"` // absent on hubs older than the dlq-names change
	RunID         string    `json:"runId"`
	SkipReason    string    `json:"skipReason"`
	ErrorMessage  string    `json:"errorMessage"`
	Attempts      int       `json:"attempts"`
	Resolution    string    `json:"resolution,omitempty"`
	CapturedAt    time.Time `json:"capturedAt"`
	Payload       string    `json:"payload,omitempty"`
	SkipDetail    string    `json:"skipDetail,omitempty"`
}

// Hook is one registered lifecycle hook: its type, the entity it targets, its
// scope, and its load status. This is the LIST endpoint's shape
// (GET /api/platform/admin/hooks, HookAdminResource.toDto) — see HookDetail for the different,
// richer shape the singular endpoint sends.
type Hook struct {
	ID     string `json:"id"`
	Type   string `json:"type"`
	Entity string `json:"entity"`
	Scope  string `json:"scope"`
	Status string `json:"status"`
}

// HookHandler is HookBinding's sealed handler, JavaHandler{className} or
// KotlinHandler{scriptPath} — Jackson encodes whichever one the hook actually uses as a plain
// object carrying that record's one field; the other is simply absent, not null.
type HookHandler struct {
	ClassName  string `json:"className,omitempty" yaml:"className,omitempty"`
	ScriptPath string `json:"scriptPath,omitempty" yaml:"scriptPath,omitempty"`
}

// HookDetail is one hook exactly as GET /api/platform/hooks/{id} returns it
// (HookResource.summarize) — a different, richer shape than Hook, the list endpoint's DTO.
type HookDetail struct {
	ID          string      `json:"id" yaml:"id"`
	Module      string      `json:"module" yaml:"module"`
	Description string      `json:"description" yaml:"description"`
	Target      string      `json:"target" yaml:"target"`
	Action      string      `json:"action" yaml:"action"`
	Events      []string    `json:"events" yaml:"events"`
	Handler     HookHandler `json:"handler" yaml:"handler"`
	Priority    int         `json:"priority" yaml:"priority"`
	Tenants     []any       `json:"tenants" yaml:"tenants"`
	Enabled     bool        `json:"enabled" yaml:"enabled"`
	ActionPhase string      `json:"actionPhase" yaml:"actionPhase"`
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

// PreflightVerdict is what one endpoint answered to `lm source rest test`: the outcome of the
// single request an ingestion run would have sent first, with nothing written.
//
// Items is what the run's own pagination strategy would have read out of that page. A 2xx with
// zero items is the shape that used to land as a green empty sync, so the pre-flight reports it
// rather than leaving OK to speak for it.
type PreflightVerdict struct {
	Endpoint string `json:"endpoint"`
	Method   string `json:"method"`
	URL      string `json:"url"`
	Status   int    `json:"status"`
	OK       bool   `json:"ok"`
	Items    int    `json:"items"`
	Detail   string `json:"detail"`
	Sample   string `json:"sample"`
}

// CursorState is an endpoint's persisted read position, used to resume
// incremental ingestion from where the last run left off.
type CursorState struct {
	EndpointID   string     `json:"endpointId"`
	Mode         string     `json:"mode"`
	CursorValue  string     `json:"cursorValue,omitempty"`
	LastSyncedAt *time.Time `json:"lastSyncedAt,omitempty"`
}
