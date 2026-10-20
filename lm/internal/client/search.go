package client

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"strconv"
)

// SearchIndexStatus is one GET /api/platform/search/index row (or the fuller GET
// /api/platform/search/index/{name}/status detail — the extra fields are omitempty so the same
// struct serves both).
type SearchIndexStatus struct {
	Name               string          `json:"name" yaml:"name"`
	Status             string          `json:"status" yaml:"status"`
	LiveGeneration     *string         `json:"liveGeneration" yaml:"liveGeneration"`
	DocumentCount      *int64          `json:"documentCount" yaml:"documentCount"`
	DocumentCountStale bool            `json:"documentCountStale" yaml:"documentCountStale"`
	DocumentCountAsOf  *string         `json:"documentCountAsOf" yaml:"documentCountAsOf"`
	LagSeconds         int64           `json:"lagSeconds" yaml:"lagSeconds"`
	ActiveRun          *SearchRunPhase `json:"activeRun,omitempty" yaml:"activeRun,omitempty"`
	LastRebuild        *SearchRunDone  `json:"lastRebuild,omitempty" yaml:"lastRebuild,omitempty"`
	LastError          string          `json:"lastError,omitempty" yaml:"lastError,omitempty"`
	Invalid            bool            `json:"invalid" yaml:"invalid"`
	InvalidReason      string          `json:"invalidReason,omitempty" yaml:"invalidReason,omitempty"`
	Withheld           bool            `json:"withheld" yaml:"withheld"`
}

// SearchRunPhase is one index's currently active run (design doc §4's activeRun object).
type SearchRunPhase struct {
	RunID     string `json:"runId" yaml:"runId"`
	Phase     string `json:"phase" yaml:"phase"`
	StartedAt string `json:"startedAt" yaml:"startedAt"`
}

// SearchRunDone is one index's most recent finished run (design doc §4's lastRebuild object).
type SearchRunDone struct {
	RunID      string `json:"runId" yaml:"runId"`
	Outcome    string `json:"outcome" yaml:"outcome"`
	FinishedAt string `json:"finishedAt" yaml:"finishedAt"`
}

// SearchIndexApplied is the hub's answer to a SearchIndex apply: the declaration's name, the
// canonical spelling of the entity it indexes, and its validity.
type SearchIndexApplied struct {
	Name   string `json:"name" yaml:"name"`
	Entity string `json:"entity" yaml:"entity"`
	Status string `json:"status" yaml:"status"`
}

// SearchIndexApply sends a raw kind:SearchIndex manifest, byte for byte.
func (c *Client) SearchIndexApply(yaml []byte) (*SearchIndexApplied, error) {
	data, err := c.doRaw("POST", "/api/search-indexes/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	var out SearchIndexApplied
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SearchIndexDelete removes one declaration; the hub writes the purge obligation in the same step.
func (c *Client) SearchIndexDelete(name string) error {
	return c.DELETE("/api/search-indexes/" + url.PathEscape(name))
}

// SearchList returns every scope-visible SearchIndex's summary.
func (c *Client) SearchList() ([]SearchIndexStatus, error) {
	var out []SearchIndexStatus
	if err := c.GET("/api/platform/search/index", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// SearchStatus returns one index's full detail.
func (c *Client) SearchStatus(name string) (*SearchIndexStatus, error) {
	var out SearchIndexStatus
	if err := c.GET("/api/platform/search/index/"+url.PathEscape(name)+"/status", &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SearchRebuildStart triggers a full rebuild and returns its runId. The hub answers 202 once the
// run is CLAIMED and durably written — never delivered.
func (c *Client) SearchRebuildStart(ctx context.Context, name string) (string, error) {
	var out struct {
		RunID string `json:"runId" yaml:"runId"`
	}
	path := "/api/platform/search/index/" + url.PathEscape(name) + "/rebuild"
	if err := c.doOnCtx(ctx, c.http, 0, "POST", path, nil, &out); err != nil {
		return "", err
	}
	if out.RunID == "" {
		return "", errors.New("the hub accepted the run but named no runId")
	}
	return out.RunID, nil
}

// SearchCancel requests cancellation of an index's active rebuild. A 202 means the request was
// RECORDED, not that the run has stopped.
func (c *Client) SearchCancel(ctx context.Context, name string) error {
	path := "/api/platform/search/index/" + url.PathEscape(name) + "/cancel"
	return c.doOnCtx(ctx, c.http, 0, "POST", path, nil, nil)
}

// SearchHit is one ranked hit of GET /api/search. Highlights carry <b>…</b> around the matched words.
type SearchHit struct {
	Entity        string              `json:"entity" yaml:"entity"`
	Key           map[string]string   `json:"key" yaml:"key"`
	Score         float64             `json:"score" yaml:"score"`
	Fields        map[string]string   `json:"fields" yaml:"fields"`
	Highlights    map[string][]string `json:"highlights" yaml:"highlights"`
	CanonicalLink string              `json:"canonicalLink" yaml:"canonicalLink"`
}

// SearchFacetCount is one value of a declared facet field and how many matches carry it.
type SearchFacetCount struct {
	Value string `json:"value" yaml:"value"`
	Count int64  `json:"count" yaml:"count"`
}

// SearchResult is the body of GET /api/search. Hits is never nil, so a JSON rendering says [] and not null.
type SearchResult struct {
	Hits       []SearchHit                   `json:"hits" yaml:"hits"`
	Facets     map[string][]SearchFacetCount `json:"facets" yaml:"facets"`
	NextCursor *string                       `json:"nextCursor" yaml:"nextCursor"`
	AsOf       *string                       `json:"asOf" yaml:"asOf"`
}

// SearchQueryParams are the inputs of GET /api/search. An empty Entity or Cursor and a nil Limit are not
// sent, so the hub's own defaults apply; a Limit that is set is sent as is, 0 and negatives included,
// because the hub owns every bound (query length, limit range, facet count) and its refusal is the answer.
type SearchQueryParams struct {
	Text   string
	Entity string
	Scope  string // narrows to the entities carrying it, like the PWA's Search tab; "" sends none

	Limit  *int
	Cursor string
	Facets [][2]string // field, value — sent as facet.<field>=<value>; the wire order is not part of the contract (url.Values sorts keys)
}

// SearchQuery runs one direct-search request.
func (c *Client) SearchQuery(p SearchQueryParams) (*SearchResult, error) {
	v := url.Values{"q": {p.Text}}
	if p.Entity != "" {
		v.Set("entity", p.Entity)
	}
	if p.Scope != "" {
		v.Set("scope", p.Scope)
	}
	if p.Limit != nil {
		v.Set("limit", strconv.Itoa(*p.Limit))
	}
	if p.Cursor != "" {
		v.Set("cursor", p.Cursor)
	}
	for _, f := range p.Facets {
		v.Add("facet."+f[0], f[1])
	}
	var out SearchResult
	if err := c.GET("/api/search?"+v.Encode(), &out); err != nil {
		return nil, err
	}
	if out.Hits == nil {
		out.Hits = []SearchHit{}
	}
	return &out, nil
}

// SearchCapability is GET /api/search/capability: whether direct Search is available, not installed
// or degraded. State is available | unavailable | degraded; the hub sends every key, null when unset.
// Zero providers ("unavailable", reason "not_installed") is the normal Open/Pro state.
type SearchCapability struct {
	State    string `json:"state" yaml:"state"`
	Provider string `json:"provider" yaml:"provider"`
	Reason   string `json:"reason" yaml:"reason"`
	Version  string `json:"version" yaml:"version"`
}

// SearchCapability asks the hub whether it has a Search provider (lm_user; always 200).
func (c *Client) SearchCapability() (*SearchCapability, error) {
	var out SearchCapability
	if err := c.GET("/api/search/capability", &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SearchIndexDeclared is one row of the scope-bound GET /api/search-indexes registry (lm_user).
type SearchIndexDeclared struct {
	Name          string  `json:"name" yaml:"name"`
	Entity        string  `json:"entity" yaml:"entity"`
	Language      string  `json:"language" yaml:"language"`
	Status        string  `json:"status" yaml:"status"`
	InvalidReason *string `json:"invalidReason" yaml:"invalidReason"`
	Withheld      bool    `json:"withheld" yaml:"withheld"`
}

// SearchIndexesDeclared lists the declarations the caller's scopes can see.
func (c *Client) SearchIndexesDeclared() ([]SearchIndexDeclared, error) {
	var out []SearchIndexDeclared
	if err := c.GET("/api/search-indexes", &out); err != nil {
		return nil, err
	}
	return out, nil
}
