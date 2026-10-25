package client

import (
	"encoding/json"
	"fmt"
	"net/url"
	"time"
)

// EntityApply sends a raw YAML entity manifest to the hub.
// Content-Type: application/yaml. Returns raw JSON response.
func (c *Client) EntityApply(yaml []byte) (json.RawMessage, error) {
	data, err := c.doRaw("POST", "/api/entities/apply", "application/yaml", "application/json", yaml)
	if err != nil {
		return nil, err
	}
	return json.RawMessage(data), nil
}

// EntityDelete removes an entity manifest via DELETE /api/entities/{name}: the hub drops the
// entity's view, deletes its manifest row, and rediscovers its scopes so the RAG card drops it.
func (c *Client) EntityDelete(name string) error {
	return c.DELETE("/api/entities/" + url.PathEscape(name))
}

// EntityResolveResult is the pass summary POST /api/entities/{name}/resolve returns — the same
// counters EntityResolutionPass.PassSummary logs server-side (#622).
type EntityResolveResult struct {
	RowsSeen           int64            `json:"rowsSeen"`
	Resolved           int64            `json:"resolved"`
	Ambiguous          int64            `json:"ambiguous"`
	UnresolvedNoRule   int64            `json:"unresolvedNoRule"`
	UnresolvedProvider int64            `json:"unresolvedProvider"`
	NewlyMinted        int64            `json:"newlyMinted"`
	ProviderCalls      int64            `json:"providerCalls"`
	ProviderFailures   int64            `json:"providerFailures"`
	RuleHits           map[string]int64 `json:"ruleHits"`
}

// EntityResolve replays identity resolution over one entity's whole extent — the explicit,
// operator-driven door (#620), for after fixing a human assertion or editing the cascade.
//
// Rides POSTSlow: the hub runs this synchronously over every row of every source the entity
// declares, which can outrun the fast client's 30s budget on a large entity long before the
// replay itself is anywhere near done — the same reason the model-bound calls in client.go do.
func (c *Client) EntityResolve(name string) (*EntityResolveResult, error) {
	var out EntityResolveResult
	if err := c.POSTSlow("/api/entities/"+url.PathEscape(name)+"/resolve", map[string]any{}, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ProposedCandidate is one candidate a candidate provider proposed and the cascade did not
// accept — never a decision, only a suggestion the resolver still scored and set aside.
type ProposedCandidate struct {
	EntityID string  `json:"entityId"`
	Score    float64 `json:"score"`
}

// SetAside is an entity the resolver set aside because it already holds another row of the row's
// source (#920). Rule is the cascade rule that found it; nil (JSON null) for a partner of a `same` assertion.
type SetAside struct {
	EntityID string  `json:"entityId"`
	Reason   string  `json:"reason"`
	Rule     *string `json:"rule"`
}

// AmbiguousIdentity is one source identity the resolution pass could not settle: two or more
// distinct candidate entities, and no later rule (or human assertion) has arbitrated yet.
type AmbiguousIdentity struct {
	IdentityRef         string              `json:"identityRef"`
	Source              string              `json:"source"`
	SourceID            string              `json:"sourceId"`
	Candidates          []string            `json:"candidates"`
	DiscardedCandidates []ProposedCandidate `json:"discardedCandidates"`
	SetAside            []SetAside          `json:"setAside"`
	DecidedBy           string              `json:"decidedBy"`
	OccurredAt          time.Time           `json:"occurredAt"`
}

// EntityAmbiguous lists identities of entityType `name` that a resolution pass left ambiguous,
// newest evidence first — the terminal-only view of #622's ambiguity punch-list.
func (c *Client) EntityAmbiguous(name string, limit, offset int) ([]AmbiguousIdentity, error) {
	path := fmt.Sprintf("/api/entities/%s/resolution/ambiguous?limit=%d&offset=%d",
		url.PathEscape(name), limit, offset)
	var out []AmbiguousIdentity
	if err := c.GET(path, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// EvidenceEvent is one entry of a source identity's append-only resolution decision ledger:
// which rule or provider fired, what it proposed, discarded and set aside, and what it decided.
type EvidenceEvent struct {
	RuleDeclaredID      string              `json:"ruleDeclaredId"`
	RuleVersion         string              `json:"ruleVersion"`
	Provider            string              `json:"provider"`
	Candidates          []string            `json:"candidates"`
	DiscardedCandidates []ProposedCandidate `json:"discardedCandidates"`
	SetAside            []SetAside          `json:"setAside"`
	Outcome             string              `json:"outcome"`
	DecidedBy           string              `json:"decidedBy"`
	RunID               string              `json:"runId"`
	OccurredAt          time.Time           `json:"occurredAt"`
	// AssertedBy and Reason name the human correction behind an asserted decision (#1343); null otherwise.
	// AssertedBy is the stored user id; AssertedByLogin the user's login read now (#1347), null when unknown.
	AssertedBy      *string `json:"assertedBy"`
	AssertedByLogin *string `json:"assertedByLogin"`
	Reason          *string `json:"reason"`
}

// EntityHistory reads one source identity's full resolution decision history, chronological
// (occurredAt, then id server-side, for a stable order when two events share an instant).
func (c *Client) EntityHistory(name, source, sourceID string) ([]EvidenceEvent, error) {
	path := fmt.Sprintf("/api/entities/%s/resolution/identity/%s/%s",
		url.PathEscape(name), url.PathEscape(source), url.PathEscape(sourceID))
	var out []EvidenceEvent
	if err := c.GET(path, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// EntityAssertion is a human correction over two rows — the {@code same}/{@code distinct}
// door's request body. Each side is an identity ref (LeftRef/RightRef) or a source handle
// "<source table>:<source id>" (Left/Right), never both (#1087). No assertedBy field: the hub
// attributes it to the authenticated caller, never to whatever this struct is given.
type EntityAssertion struct {
	LeftRef  string `json:"leftRef,omitempty"`
	RightRef string `json:"rightRef,omitempty"`
	Left     string `json:"left,omitempty"`
	Right    string `json:"right,omitempty"`
	Reason   string `json:"reason,omitempty"`
}

// EntityAssertSame records that leftRef and rightRef are the same real-world entity.
func (c *Client) EntityAssertSame(name string, a EntityAssertion) error {
	return c.entityAssert(name, "same", a)
}

// EntityAssertDistinct records that leftRef and rightRef are different real-world entities —
// the correction that outranks every cascade rule (design doc §7).
func (c *Client) EntityAssertDistinct(name string, a EntityAssertion) error {
	return c.entityAssert(name, "distinct", a)
}

func (c *Client) entityAssert(name, verb string, a EntityAssertion) error {
	return c.POST("/api/entities/"+url.PathEscape(name)+"/assertions/"+verb, a, nil)
}

// EntitySummary is one entity GET /api/entities lists: what a client needs to pick one and read it.
type EntitySummary struct {
	Name       string   `json:"name"`
	Label      string   `json:"label,omitempty"`
	Scopes     []string `json:"scopes"`
	Sources    []string `json:"sources"`
	Resolution bool     `json:"resolution"`
	Columns    []string `json:"columns"`
}

// Mode is how the entity fuses its sources: "resolution" or "match".
func (e EntitySummary) Mode() string {
	if e.Resolution {
		return "resolution"
	}
	return "match"
}

// EntityList lists the entities the caller may read (a bound caller sees only its scopes).
func (c *Client) EntityList() ([]EntitySummary, error) {
	var out []EntitySummary
	if err := c.GET("/api/entities", &out); err != nil {
		return nil, err
	}
	return out, nil
}
