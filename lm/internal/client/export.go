package client

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ExportRunsPageCap mirrors ExportRunResource.list, which clamps ?limit to 1..200.
const ExportRunsPageCap = 200

// ExportApplied is the hub's answer to a kind:Export apply: the stored name (lower-cased), its
// scope, and the entity's canonical spelling — the one `list` and `get` show afterwards.
type ExportApplied struct {
	Name   string `json:"name" yaml:"name"`
	Scope  string `json:"scope" yaml:"scope"`
	Entity string `json:"entity" yaml:"entity"`
}

// ExportSummary is one row of GET /api/exports (ExportManifestStore.Summary).
type ExportSummary struct {
	Name            string `json:"name" yaml:"name"`
	Scope           string `json:"scope" yaml:"scope"`
	Entity          string `json:"entityName" yaml:"entityName"`
	DestinationKind string `json:"destinationKind" yaml:"destinationKind"`
	Schedule        string `json:"schedule,omitempty" yaml:"schedule,omitempty"`
}

// ExportRun is one connector.export_run ledger row (ExportRunView). The receipt fields are set
// only on a Completed run, failureCode/failureReason only on a Failed one.
type ExportRun struct {
	ID              string     `json:"id" yaml:"id"`
	Export          string     `json:"export" yaml:"export"`
	Scope           string     `json:"scope" yaml:"scope"`
	Entity          string     `json:"entity" yaml:"entity"`
	DestinationKind string     `json:"destinationKind" yaml:"destinationKind"`
	Trigger         string     `json:"trigger" yaml:"trigger"`
	TriggeredBy     *int64     `json:"triggeredBy,omitempty" yaml:"triggeredBy,omitempty"`
	Occurrence      *time.Time `json:"occurrence,omitempty" yaml:"occurrence,omitempty"`
	Attempt         int        `json:"attempt" yaml:"attempt"`
	RetryOf         string     `json:"retryOf,omitempty" yaml:"retryOf,omitempty"`
	Status          string     `json:"status" yaml:"status"`
	StartedAt       *time.Time `json:"startedAt,omitempty" yaml:"startedAt,omitempty"`
	EndedAt         *time.Time `json:"endedAt,omitempty" yaml:"endedAt,omitempty"`
	RowCount        *int64     `json:"rowCount,omitempty" yaml:"rowCount,omitempty"`
	ByteCount       *int64     `json:"byteCount,omitempty" yaml:"byteCount,omitempty"`
	Sha256          string     `json:"sha256,omitempty" yaml:"sha256,omitempty"`
	Location        string     `json:"location,omitempty" yaml:"location,omitempty"`
	VersionID       string     `json:"versionId,omitempty" yaml:"versionId,omitempty"`
	ETag            string     `json:"etag,omitempty" yaml:"etag,omitempty"`
	FailureCode     string     `json:"failureCode,omitempty" yaml:"failureCode,omitempty"`
	FailureReason   string     `json:"failureReason,omitempty" yaml:"failureReason,omitempty"`
}

// The four statuses the ledger's CHECK constraint allows (ExportRunStatus).
const (
	ExportRunQueued    = "Queued"
	ExportRunRunning   = "Running"
	ExportRunCompleted = "Completed"
	ExportRunFailed    = "Failed"
)

// ExportApply sends a raw kind:Export manifest, byte for byte. The hub refuses inline credentials
// with a message naming the field only, so a refusal never echoes the value back.
func (c *Client) ExportApply(yaml []byte) (*ExportApplied, error) {
	data, err := c.doRaw("POST", "/api/exports/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	var out ExportApplied
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ExportList returns the exports the caller's scopes can see.
func (c *Client) ExportList() ([]ExportSummary, error) {
	var out []ExportSummary
	if err := c.GET("/api/exports", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ExportManifest fetches one export's stored manifest YAML, verbatim.
func (c *Client) ExportManifest(name string) ([]byte, error) {
	// JSON fallback in Accept: error bodies (403/404) are JSON — a yaml-only Accept could 406 them.
	return c.doRaw("GET", "/api/exports/"+url.PathEscape(name), "",
		"application/yaml, application/json;q=0.9", nil)
}

// ExportRunStart asks the hub for a manual run and returns its id. The hub answers 202 once the
// run is CLAIMED, not delivered: the returned id names a row the hub has accepted
// (Queued or Running; read it back with ExportRunGet).
func (c *Client) ExportRunStart(ctx context.Context, name string) (string, error) {
	return c.exportStart(ctx, exportRunsPath(name))
}

// ExportRunRetry starts a new attempt of a Failed run and returns the new run's id.
func (c *Client) ExportRunRetry(ctx context.Context, name, runID string) (string, error) {
	return c.exportStart(ctx, exportRunsPath(name)+"/"+url.PathEscape(runID)+"/retry")
}

func (c *Client) exportStart(ctx context.Context, path string) (string, error) {
	var out struct {
		RunID string `json:"runId" yaml:"runId"`
	}
	if err := c.doOnCtx(ctx, c.http, 0, "POST", path, nil, &out); err != nil {
		return "", err
	}
	if out.RunID == "" {
		return "", errors.New("the hub accepted the run but named no runId")
	}
	return out.RunID, nil
}

// ExportRuns lists an export's runs, newest first. The hub clamps limit to ExportRunsPageCap.
func (c *Client) ExportRuns(ctx context.Context, name string, limit int) ([]ExportRun, error) {
	var out []ExportRun
	path := exportRunsPath(name) + "?limit=" + strconv.Itoa(limit)
	if err := c.doOnCtx(ctx, c.http, 0, "GET", path, nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ExportRunGet reads one run of an export.
func (c *Client) ExportRunGet(ctx context.Context, name, runID string) (*ExportRun, error) {
	var out ExportRun
	path := exportRunsPath(name) + "/" + url.PathEscape(runID)
	if err := c.doOnCtx(ctx, c.http, 0, "GET", path, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// InFlightExportRun reports the run a 409 "already has a run in flight" named, if it named one.
// The hub omits runId when only the run lock answered, so ok with an empty id is possible.
func InFlightExportRun(err error) (runID string, inFlight bool) {
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 409 {
		return "", false
	}
	var body struct {
		Error string `json:"error" yaml:"error"`
		RunID string `json:"runId" yaml:"runId"`
	}
	if json.Unmarshal([]byte(apiErr.Body), &body) != nil || body.Error == "" {
		return "", false
	}
	// "already delivered" and "only a Failed run can be retried" are 409s too, without a run in
	// flight: only the in-flight refusal carries the runId key, or says so.
	if body.RunID == "" && !strings.Contains(body.Error, "run in flight") {
		return "", false
	}
	return body.RunID, true
}

func exportRunsPath(name string) string {
	return "/api/exports/" + url.PathEscape(name) + "/runs"
}
