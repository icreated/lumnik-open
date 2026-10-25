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

// TableRows is one table a connector delete would drop or empty, and the rows that would go with it:
// the hub's dry run (#1218).
type TableRows struct {
	Table string `json:"table"`
	Rows  int    `json:"rows"`
}

// DeleteTakes is what a connector delete would take, from the hub's dry run: its tables, and its
// credential when it is the last connector authenticating with it (#1250).
type DeleteTakes struct {
	Tables     []TableRows `json:"tables"`
	Credential string      `json:"credential,omitempty"`
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
//
// RecordsRemoved is outside the identity too: rows a csv run that read its whole file removed
// because the file no longer holds them (#1195), or the older versions of a key a jdbc table's
// first keyed run removed (#1196). A hub older than this field omits it: 0.
type Run struct {
	ID               string     `json:"id"`
	ConnectorID      string     `json:"connectorId"`
	ConnectorName    string     `json:"connectorName"` // absent on hubs older than the ledger-names change
	EndpointID       string     `json:"endpointId"`    // empty for a run with no endpoint, and on older hubs
	EndpointName     string     `json:"endpointName"`
	Status           string     `json:"status"`
	StartedAt        *time.Time `json:"startedAt,omitempty"` // nil while the run is still queued
	EndedAt          *time.Time `json:"endedAt,omitempty"`
	RecordsIn        int64      `json:"recordsIn"`
	RecordsOut       int64      `json:"recordsOut"`
	RecordsSkipped   int64      `json:"recordsSkipped"`
	CellErrors       int64      `json:"cellErrors"`
	RecordsRemoved   int64      `json:"recordsRemoved"`
	FilesUnchanged   int64      `json:"filesUnchanged"`   // #1371: not read, unchanged since read to the end
	FilesNotArchived int64      `json:"filesNotArchived"` // #1382: read, after_process failed, still in the source
	TriggeredBy      string     `json:"triggeredBy"`
	ChunkCount       int        `json:"chunkCount"`
	ErrorMessage     string     `json:"errorMessage"`
	DeadLetterCount  int64      `json:"deadLetterCount"`
	// #1430: each version of a Kotlin script the run's rows went through, in the order first run.
	Scripts []RunScript `json:"scripts"`
	// Evaluated says whether kind:Process has evaluated the rows the run wrote (#1351): nil when
	// the tenant has no enabled Process, one changed after the run started, the run ended before
	// the hub recorded it, or the hub is older.
	Evaluated *bool `json:"evaluated,omitempty"`
}

// RunScript is one version of a Kotlin script a run ran (#1430): the reference the manifest names it
// by (hooks:shipping-fee.kts) and the SHA-256 of its text — for a hooks: script, the fingerprint
// `lm hook scripts` shows. A hub older than this field omits it: null in `-o json`.
type RunScript struct {
	Name   string `json:"name"`
	SHA256 string `json:"sha256"`
}

// EvaluatedLabel is what a human should read in an EVALUATED column (#1351): "—" whenever
// Evaluated is nil (see there).
func (r Run) EvaluatedLabel() string {
	switch {
	case r.Evaluated == nil:
		return "—"
	case *r.Evaluated:
		return "yes"
	default:
		return "no"
	}
}

// AnyEvaluated says whether the hub answered for some run: a table shows EVALUATED only then, so a
// tenant without an enabled Process keeps the table it always had.
func AnyEvaluated(runs []Run) bool {
	for _, r := range runs {
		if r.Evaluated != nil {
			return true
		}
	}
	return false
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
	ID            string     `json:"id"`
	ConnectorID   string     `json:"connectorId"`
	ConnectorName string     `json:"connectorName"` // absent on hubs older than the dlq-names change
	RunID         string     `json:"runId"`
	SkipReason    string     `json:"skipReason"`
	ErrorMessage  string     `json:"errorMessage"`
	Attempts      int        `json:"attempts"`
	Resolution    string     `json:"resolution,omitempty"`
	ResolvedRunID string     `json:"resolvedRunId,omitempty"`
	ResolvedAt    *time.Time `json:"resolvedAt,omitempty"`
	CapturedAt    time.Time  `json:"capturedAt"`
	Payload       string     `json:"payload,omitempty"`
	SkipDetail    string     `json:"skipDetail,omitempty"`
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

// HookScripts is the hub's hooks directory as GET /api/platform/hooks/scripts sends it (#1402):
// Directory false means the operator set none, which an empty Scripts alone could not say.
type HookScripts struct {
	Directory bool         `json:"directory" yaml:"directory"`
	Scripts   []HookScript `json:"scripts" yaml:"scripts"`
}

// HookScript is one script a manifest can name as `script: hooks:<name>`.
type HookScript struct {
	Name   string `json:"name" yaml:"name"`
	Size   int64  `json:"size" yaml:"size"`
	SHA256 string `json:"sha256" yaml:"sha256"`
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
//
// LastApplied is the hub's RFC3339 string, kept as a string: a schema with no applied migration
// comes as "" (#1149), which a time.Time refuses — and the whole reply with it. "" or absent means
// never; render it with output.HumanTimeStrOr(s.LastApplied, "—").
type MigrationStatus struct {
	Schema         string `json:"schema"`
	CurrentVersion string `json:"currentVersion"`
	Pending        int    `json:"pending"`
	LastApplied    string `json:"lastApplied"`
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
