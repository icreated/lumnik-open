//go:build otk

package otk

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/cucumber/godog"
	otkauth "github.com/icreated/lumnik-open/lm/test/otk/auth"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
	"github.com/icreated/lumnik-open/lm/test/otk/runner"
)

const (
	stepEntityGivenApply  = `^an entity manifest for variant "([^"]*)"(?: \[seed=(-?\d+)\])?$`
	stepEntityWhenApply   = `^I apply the entity manifest as (lm_admin|lm_user)$`
	stepEntityNotStatus   = `^the entity apply response status is not (\d+)$`
	stepEntityExactStatus = `^the entity apply response status is (\d+)$`
	stepEntityGivenRead   = `^the seed entity "([^"]*)" exists for entity read(?: \[seed=(-?\d+)\])?$`
	stepEntityWhenRead    = `^I read entity "([^"]*)" with query "([^"]*)"( as cross-tenant)?$`
	stepEntityReadStatus  = `^the entity read response status is (\d+)$`
	stepEntityReadSafe    = `^the entity read response is safe$`
	stepEntityReadRows    = `^the entity read response has at most (\d+) rows$`
	stepEntityNoRows      = `^the entity read response contains no rows \(RLS\)$`
	stepEntityCleanup     = `^wrong-role-apply must not return 200$`

	stepEntityCliGiven   = `^the entity CLI fixture is seeded with two genuine ambiguities(?: \[seed=(-?\d+)\])?$`
	stepEntityCliWhen    = `^I drive the entity resolution CLI doors end-to-end$`
	stepEntityCliCleared = `^lm entity ambiguous no longer lists either fixture identity$`
	stepEntityCliHistory = `^lm entity history shows both fixture identities were cleared by an assertion$`
)

func init() {
	stepPatterns = append(stepPatterns,
		stepEntityGivenApply, stepEntityWhenApply, stepEntityNotStatus, stepEntityExactStatus,
		stepEntityGivenRead, stepEntityWhenRead, stepEntityReadStatus, stepEntityReadSafe,
		stepEntityReadRows, stepEntityNoRows, stepEntityCleanup,
		stepEntityCliGiven, stepEntityCliWhen, stepEntityCliCleared, stepEntityCliHistory)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepEntityGivenApply, func(variant, seedStr string) error { return entityGivenApply(st, variant, seedStr) })
		ctx.Step(stepEntityWhenApply, func(role string) error { return entityWhenApply(st, role) })
		ctx.Step(stepEntityNotStatus, func(code int) error { return entityNotStatus(st, code) })
		ctx.Step(stepEntityExactStatus, func(code int) error { return entityExactStatus(st, code) })
		ctx.Step(stepEntityGivenRead, func(name, seedStr string) error { return entityGivenRead(st, name, seedStr) })
		ctx.Step(stepEntityWhenRead, func(name, query, suffix string) error { return entityWhenRead(st, name, query, suffix != "") })
		ctx.Step(stepEntityReadStatus, func(code int) error { return entityReadStatusStep(st, code) })
		ctx.Step(stepEntityReadSafe, func() error { return entityReadSafe(st) })
		ctx.Step(stepEntityReadRows, func(max int) error { return entityReadRows(st, max) })
		ctx.Step(stepEntityNoRows, func() error { return entityNoRows(st) })
		ctx.Step(stepEntityCleanup, func() error { return entityWrongRoleCleanup(st) })
		ctx.Step(stepEntityCliGiven, func(seedStr string) error { return entityCliGiven(st, seedStr) })
		ctx.Step(stepEntityCliWhen, func() error { return entityCliWhen(st) })
		ctx.Step(stepEntityCliCleared, func() error { return entityCliThenCleared(st) })
		ctx.Step(stepEntityCliHistory, func() error { return entityCliThenHistory(st) })
	})
}

// parseSeed parses a seed string like "42" from a Gherkin [seed=N] capture.
// Returns 0 when s is empty (catalog variant without an explicit seed).
func parseSeed(s string) int64 {
	if s == "" {
		return 0
	}
	n, _ := strconv.ParseInt(s, 10, 64)
	return n
}

// entityHTTPClient returns a short-timeout HTTP client for direct entity API calls.
func entityHTTPClient() *http.Client {
	return &http.Client{Timeout: 15 * time.Second}
}

// entityBaseURL returns the base LUMNIK_URL, trimmed of trailing slash.
func entityBaseURL() string {
	return strings.TrimRight(os.Getenv("LUMNIK_URL"), "/")
}

// entityApplyHTTP POSTs a YAML manifest to /api/entities/apply with the given bearer token.
// Returns (statusCode, responseBody, error). A network-level error is returned as error.
func entityApplyHTTP(yamlBytes []byte, token string) (int, string, error) {
	endpointURL := entityBaseURL() + "/api/entities/apply"
	req, err := http.NewRequest("POST", endpointURL, bytes.NewReader(yamlBytes))
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("Content-Type", "application/yaml")
	req.Header.Set("Accept", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	// Always send the OTK tenant header so the hub can scope the entity.
	req.Header.Set("X-Tenant-ID", otkTenant)
	req.Header.Set("X-User-ID", otkTenant)

	cl := entityHTTPClient()
	resp, err := cl.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b), nil
}

// entityReadHTTP GETs /api/entities/{name}?{query} with the given bearer token.
// Returns (statusCode, responseBody, error).
func entityReadHTTP(name, query, token string) (int, string, error) {
	path := entityBaseURL() + "/api/entities/" + url.PathEscape(name)
	if query != "" {
		path += "?" + query
	}
	req, err := http.NewRequest("GET", path, nil)
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("Accept", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("X-Tenant-ID", otkTenant)
	req.Header.Set("X-User-ID", otkTenant)

	cl := entityHTTPClient()
	resp, err := cl.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b), nil
}

// currentToken returns the OTK integrator token for tenant 1. Under dev-bypass it is
// the plain LUMNIK_TOKEN string (the hub ignores it). Under real-auth it is a minted JWT.
func currentToken() (string, error) {
	if otkKey != nil {
		return otkKey.Integrator(otkTenant, otkTenant)
	}
	return envOr("LUMNIK_TOKEN", "dev-token"), nil
}

// ---- Given steps ----

func entityGivenApply(st *scenarioState, variant, seedStr string) error {
	yamlStr, useUserRole, _, caseID, found := generator.EntityApplyManifest(variant)
	if !found {
		return fmt.Errorf("entity lane: unknown apply variant %q", variant)
	}
	st.entityScen = generator.EntityScenario{
		Seed:         parseSeed(seedStr),
		CaseID:       caseID,
		SubType:      "apply",
		ManifestYAML: yamlStr,
		Variant:      variant,
		UseUserRole:  useUserRole,
	}
	return nil
}

// entityGivenRead records the seed entity name but does no live operation.
// The actual entity is assumed to exist in the hub for the relevant read-sub-types;
// the "unknown-entity" sub-type intentionally targets a ghost name.
func entityGivenRead(st *scenarioState, name, seedStr string) error {
	st.entityScen = generator.EntityScenario{
		Seed:       parseSeed(seedStr),
		SubType:    "read",
		ReadTarget: name,
	}
	return nil
}

// ---- When steps ----

func entityWhenApply(st *scenarioState, role string) error {
	if entityBaseURL() == "" {
		// No hub — these steps are gated by the outer TestOTK skip guard.
		return fmt.Errorf("entity lane: LUMNIK_URL is not set")
	}

	var token string
	if role == "lm_user" {
		// Wrong-role-apply: requires real-auth to forge a non-integrator token.
		if otkKey == nil {
			// Dormant: real-auth not configured.
			recordDormant(st.entityScen.CaseID, "wrong-role-apply: set OTK_OIDC_ISSUER + boot %otkauth")
			st.entityApplyStatus = -1 // sentinel: dormant
			return nil
		}
		var err error
		token, err = otkKey.Forge(otkauth.WrongRole, 0)
		if err != nil {
			return fmt.Errorf("entity lane: forge wrong-role token: %w", err)
		}
	} else {
		var err error
		token, err = currentToken()
		if err != nil {
			return fmt.Errorf("entity lane: mint integrator token: %w", err)
		}
	}

	status, _, err := entityApplyHTTP([]byte(st.entityScen.ManifestYAML), token)
	if err != nil {
		return fmt.Errorf("entity lane: POST /api/entities/apply: %w", err)
	}
	st.entityApplyStatus = status
	return nil
}

// entityWhenRead issues GET /api/entities/{name}?{query}. crossTenant is true
// when the Gherkin step text contains " as cross-tenant" (captured as a suffix group).
func entityWhenRead(st *scenarioState, name, query string, crossTenant bool) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("entity lane: LUMNIK_URL is not set")
	}

	// The Given step only captures the seed entity name (see entityGivenRead) — the
	// read variant's CaseID cannot be recovered until readTarget, readQuery and
	// crossTenant are all known, which happens only here.
	if caseID, found := generator.EntityReadCaseID(name, query, crossTenant); found {
		st.entityScen.CaseID = caseID
	}

	var token string
	if crossTenant {
		if otkKey == nil {
			recordDormant(st.entityScen.CaseID, "cross-tenant-read: set OTK_OIDC_ISSUER + boot %otkauth")
			st.entityReadStatus = -1 // sentinel: dormant
			return nil
		}
		var err error
		token, err = otkKey.Forge(otkauth.CrossTenant, 0)
		if err != nil {
			return fmt.Errorf("entity lane: forge cross-tenant token: %w", err)
		}
	} else {
		var err error
		token, err = currentToken()
		if err != nil {
			return fmt.Errorf("entity lane: mint integrator token: %w", err)
		}
	}

	// Read variants that require a real entity in the DB (sqli-in-filter, huge-limit,
	// cross-tenant-read) are dormant when OTK_DB_DSN is absent, except unknown-entity
	// which only needs the hub to exist.
	if name != "ghost_otk_seed" {
		if os.Getenv("OTK_DB_DSN") == "" {
			recordDormant(st.entityScen.CaseID, fmt.Sprintf("variant read-target %q: set OTK_DB_DSN to seed test entities", name))
			st.entityReadStatus = -1 // sentinel: dormant
			return nil
		}
	}

	status, body, err := entityReadHTTP(name, query, token)
	if err != nil {
		return fmt.Errorf("entity lane: GET /api/entities/%s: %w", name, err)
	}
	st.entityReadStatus = status
	st.entityReadBody = body
	return nil
}

// ---- Then steps ----

func entityNotStatus(st *scenarioState, code int) error {
	if st.entityApplyStatus == -1 {
		return nil // dormant
	}
	if st.entityApplyStatus == 0 {
		return fmt.Errorf("entity lane: apply was never called (status=0)")
	}
	if st.entityApplyStatus == code {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High,
			Family:   oracle.Security,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity apply returned %d (must not be %d)", st.entityApplyStatus, code),
		})
	}
	return nil
}

func entityExactStatus(st *scenarioState, code int) error {
	if st.entityApplyStatus == -1 {
		return nil // dormant
	}
	if st.entityApplyStatus == 0 {
		return fmt.Errorf("entity lane: apply was never called (status=0)")
	}
	if st.entityApplyStatus != code {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High,
			Family:   oracle.Security,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity apply status: got %d, want %d", st.entityApplyStatus, code),
		})
	}
	return nil
}

// entityWrongRoleCleanup adds a CRITICAL finding if the wrong-role-apply returned 200.
// The step name from the Gherkin is "wrong-role-apply must not return 200".
func entityWrongRoleCleanup(st *scenarioState) error {
	if st.entityApplyStatus == -1 {
		return nil // dormant
	}
	if st.entityApplyStatus == 200 {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Critical,
			Family:   oracle.Security,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   "wrong-role entity apply returned 200 (role escalation — must be 403)",
		})
	}
	return nil
}

func entityReadStatusStep(st *scenarioState, code int) error {
	if st.entityReadStatus == -1 {
		return nil // dormant
	}
	if st.entityReadStatus == 0 {
		return fmt.Errorf("entity lane: read was never called (status=0)")
	}
	if st.entityReadStatus != code {
		// 404-expected mismatches are robustness (unknown-entity returning wrong code);
		// 200-expected mismatches are security (something unexpectedly failed).
		sev := oracle.High
		fam := oracle.Security
		if code == 404 {
			sev = oracle.Low
			fam = oracle.Robustness
		}
		st.findings = append(st.findings, oracle.Finding{
			Severity: sev,
			Family:   fam,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity read status: got %d, want %d", st.entityReadStatus, code),
		})
	}
	return nil
}

// entityReadSafe checks that the response body does not contain injected content
// (SQL injection reflection). We look for the canonical DROP TABLE marker.
func entityReadSafe(st *scenarioState) error {
	if st.entityReadStatus == -1 {
		return nil // dormant
	}
	if strings.Contains(st.entityReadBody, "DROP TABLE") {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High,
			Family:   oracle.Security,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   "entity read response contains injected SQL content (DROP TABLE reflected)",
		})
	}
	return nil
}

// entityReadRows checks that the response contains at most max rows.
func entityReadRows(st *scenarioState, max int) error {
	if st.entityReadStatus == -1 {
		return nil // dormant
	}
	var rows []json.RawMessage
	if err := json.Unmarshal([]byte(st.entityReadBody), &rows); err != nil {
		// Not a JSON array — graded as a robustness finding.
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High,
			Family:   oracle.Robustness,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity read response is not a JSON array: %v", err),
		})
		return nil
	}
	if len(rows) > max {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Low,
			Family:   oracle.Robustness,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity read returned %d rows, want at most %d (huge-limit not capped)", len(rows), max),
		})
	}
	return nil
}

// entityNoRows checks that the response is an empty JSON array (RLS isolation).
func entityNoRows(st *scenarioState) error {
	if st.entityReadStatus == -1 {
		return nil // dormant
	}
	var rows []json.RawMessage
	if err := json.Unmarshal([]byte(st.entityReadBody), &rows); err != nil {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High,
			Family:   oracle.Robustness,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity read (cross-tenant) response is not a JSON array: %v", err),
		})
		return nil
	}
	if len(rows) != 0 {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Critical,
			Family:   oracle.Security,
			CaseID:   st.entityScen.CaseID,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("cross-tenant entity read returned %d rows (RLS isolation breach)", len(rows)),
		})
	}
	return nil
}

// ---- entity lane, cli sub-type (#647) ----
//
// Happy-path coverage for all FIVE #623 CLI doors (lm entity resolve/ambiguous/history/
// assert-same/assert-distinct — icreated's P1 review on PR #666 caught that the first version
// of this scenario only drove four of the five, despite claiming all five): two independent
// genuine ambiguities are manufactured via DB-truth (the same ghost-fingerprint-copy technique
// steps_entity_resolution.go's "ambiguous-mailbox-never-merges" case uses — natural resolution
// always auto-merges a second colliding row, so a real ambiguity requires two ALREADY-resolved
// identities sharing one fingerprint), one cleared by assert-same, the other by assert-distinct,
// both driven by the actual lm binary end-to-end. Reuses resEntityName/resTable/resTableB
// (already applied once per TestOTK run by ensureEntityResolutionSeed) and
// steps_entity_resolution.go's DB-truth helpers, with its own seed-suffixed "cli-" ids so it
// never collides with that lane's own c1/c2/c3 fixtures. Both target rows live on resTableB
// while every ghost lives on resTable — a different physical source, same reason the
// entity-resolution lane's own case 2 splits its pair (see entityCliGiven).
//
// This is functional/happy-path coverage only — the adversarial battery over the REST doors
// directly (ghost ids, malformed refs, cross-tenant probes) is #643's job, not this one's.

// entityCliSeedGhostPair manufactures one genuine ambiguity precondition: two ALREADY-resolved
// ghost identities sharing a real "email" fingerprint, via a throwaway row this harness resolves
// once purely to compute that fingerprint (deleted right after, per resSeedGhost's doc comment —
// it has no way to derive the HMAC itself).
func entityCliSeedGhostPair(token, seedRow, ghostA, ghostB, email string) error {
	if err := resResetIdentity(resTable, seedRow); err != nil {
		return err
	}
	if err := resUpsertRow(resTable, seedRow, email); err != nil {
		return err
	}
	if status, body, err := entityResolveHTTP(resEntityName, token); err != nil {
		return err
	} else if status != 200 {
		return fmt.Errorf("entity CLI lane: fixture setup resolve returned %d: %s", status, body)
	}
	if err := resSeedGhost(ghostA, seedRow, "email"); err != nil {
		return err
	}
	if err := resSeedGhost(ghostB, seedRow, "email"); err != nil {
		return err
	}
	return resDeleteRow(resTable, seedRow)
}

func entityCliGiven(st *scenarioState, seedStr string) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("entity CLI lane: LUMNIK_URL is not set")
	}
	seed := parseSeed(seedStr)
	caseID, found := generator.EntityCliCaseID("resolve-ambiguous-assert-same-assert-distinct-history")
	if !found {
		return fmt.Errorf("entity CLI lane: unknown cli variant")
	}
	st.entityScen = generator.EntityScenario{Seed: seed, CaseID: caseID, SubType: "cli"}

	if os.Getenv("OTK_DB_DSN") == "" {
		recordDormant(caseID, "entity CLI lane: set OTK_DB_DSN + psql "+
			"(drives DB-truth ghost seeding, like the entity-resolution lane)")
		st.entityCliDormant = true
		return nil
	}
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("entity CLI lane: mint integrator token: %w", err)
	}

	// resResetIdentity before every seed: a rerun against the same persistent DB (weekly Gold,
	// say) must never inherit a prior run's ambiguous/asserted history — see its doc comment
	// (icreated's P2 review on PR #664, the same lesson applied here from the start).

	// Pair 1 (Alice/Bob) -> target: the assert-same happy path.
	ghostAlice := fmt.Sprintf("cli-alice-ghost-%d", seed)
	ghostBob := fmt.Sprintf("cli-bob-ghost-%d", seed)
	target := fmt.Sprintf("cli-target-%d", seed)
	email1 := fmt.Sprintf("cli-sharedbox-%d@otk.test", seed)
	if err := entityCliSeedGhostPair(token, fmt.Sprintf("cli-seed1-%d", seed), ghostAlice, ghostBob, email1); err != nil {
		return err
	}

	// Pair 2 (Carol/Dave) -> target2: the assert-distinct happy path. A wholly independent
	// fingerprint (a different email) so it can never interact with pair 1's candidates.
	ghostCarol := fmt.Sprintf("cli-carol-ghost-%d", seed)
	ghostDave := fmt.Sprintf("cli-dave-ghost-%d", seed)
	target2 := fmt.Sprintf("cli-target2-%d", seed)
	email2 := fmt.Sprintf("cli-distinctbox-%d@otk.test", seed)
	if err := entityCliSeedGhostPair(token, fmt.Sprintf("cli-seed2-%d", seed), ghostCarol, ghostDave, email2); err != nil {
		return err
	}

	// target/target2 live on resTableB, a DIFFERENT physical source than every ghost (resTable) —
	// see resTableB's own doc comment (steps_entity_resolution.go): once an assertion moves a
	// target's entity_id to one of its ghosts', two rows of the SAME source sharing one entity_id
	// would violate uq_entity_identity_cardinality UNIQUE(tenant_id, entity_type, source,
	// entity_id). Splitting across sources is exactly how the entity-resolution lane's own case 2
	// avoids the identical collision. candidateEntityIds() has no source filter, so a target still
	// sees its ghosts as candidates regardless of which source it lives on.
	if err := resResetIdentity(resTableB, target); err != nil {
		return err
	}
	if err := resUpsertRow(resTableB, target, email1); err != nil {
		return err
	}
	if err := resResetIdentity(resTableB, target2); err != nil {
		return err
	}
	if err := resUpsertRow(resTableB, target2, email2); err != nil {
		return err
	}

	st.entityCliTargetID = target
	st.entityCliGhostID = ghostAlice
	st.entityCliTarget2ID = target2
	st.entityCliGhostDave = ghostDave
	st.entityCliGhostCarol = ghostCarol
	return nil
}

// entityCliFinding appends a Critical/Security finding — same severity/family as
// steps_entity_resolution.go's resFinding for the analogous "an identity ended up somewhere the
// resolver's own contract forbids" class, since a CLI door silently failing to clear a genuine
// ambiguity is exactly that: the human-correction path (#619) not working end-to-end.
func entityCliFinding(st *scenarioState, detail string) {
	st.findings = append(st.findings, oracle.Finding{
		Severity: oracle.Critical, Family: oracle.Security,
		CaseID: st.entityScen.CaseID, Seed: st.entityScen.Seed,
		Detail: detail,
	})
}

// entityCliRunAmbiguous runs `lm entity ambiguous` and parses its JSON output, reusing
// steps_entity_resolution.go's ambiguousRow shape (same field names as the REST door's payload).
func entityCliRunAmbiguous() ([]ambiguousRow, error) {
	r := runner.Run(lmEnv(), lmBinary(), "entity", "ambiguous", resEntityName, "-o", "json")
	if r.Exit != 0 {
		return nil, fmt.Errorf("entity CLI lane: lm entity ambiguous failed (exit %d): %s%s", r.Exit, r.Stdout, r.Stderr)
	}
	var rows []ambiguousRow
	if err := json.Unmarshal([]byte(r.Stdout), &rows); err != nil {
		return nil, fmt.Errorf("entity CLI lane: could not parse lm entity ambiguous output: %w", err)
	}
	return rows, nil
}

// entityCliAmbiguousRefFor finds name's row in an already-fetched ambiguous listing and returns
// its identity_ref, failing loud (a finding) if it is missing or under-candidated — the two ways
// "lm entity resolve did not do its job" would otherwise pass silently.
func entityCliAmbiguousRefFor(st *scenarioState, rows []ambiguousRow, sourceID string) (string, bool) {
	row := findRow(rows, sourceID)
	if row == nil {
		entityCliFinding(st, fmt.Sprintf(
			"lm entity resolve did not leave %q ambiguous — lm entity ambiguous does not list it", sourceID))
		return "", false
	}
	if len(row.Candidates) < 2 {
		entityCliFinding(st, fmt.Sprintf(
			"%q is listed ambiguous but carries %d candidate(s), want >= 2 (candidates=%v)",
			sourceID, len(row.Candidates), row.Candidates))
		return "", false
	}
	return row.IdentityRef, true
}

// entityCliWhen drives all FIVE CLI doors: resolve (to turn both fixture pairs into genuine
// ambiguities), ambiguous (to find them and their candidates), assert-same on pair 1 and
// assert-distinct on pair 2 (the two human-correction doors), then resolve again (the replay
// that should now see both assertions). history is checked separately in entityCliThenHistory,
// once the flow is complete.
func entityCliWhen(st *scenarioState) error {
	if st.entityCliDormant {
		return nil
	}
	if entityBaseURL() == "" {
		return fmt.Errorf("entity CLI lane: LUMNIK_URL is not set")
	}

	r := runner.Run(lmEnv(), lmBinary(), "entity", "resolve", resEntityName)
	if r.Exit != 0 {
		return fmt.Errorf("entity CLI lane: lm entity resolve failed (exit %d): %s%s", r.Exit, r.Stdout, r.Stderr)
	}

	rows, err := entityCliRunAmbiguous()
	if err != nil {
		return err
	}
	targetRef, ok := entityCliAmbiguousRefFor(st, rows, st.entityCliTargetID)
	if !ok {
		return nil
	}
	target2Ref, ok := entityCliAmbiguousRefFor(st, rows, st.entityCliTarget2ID)
	if !ok {
		return nil
	}

	// Both doors need an identity_ref, not a raw entity_id — each ghost's own ref is known
	// directly from the Given step (it was seeded there), so no round trip through either
	// candidate list is needed (#622's REST surface has no door that hands one back for a
	// resolved, non-ambiguous row anyway).
	ghostRef, err := resIdentityRef(resTable, st.entityCliGhostID)
	if err != nil {
		return fmt.Errorf("entity CLI lane: %w", err)
	}
	ghostDistinctRef, err := resIdentityRef(resTable, st.entityCliGhostDave)
	if err != nil {
		return fmt.Errorf("entity CLI lane: %w", err)
	}

	r = runner.Run(lmEnv(), lmBinary(), "entity", "assert-same", resEntityName,
		"--left", targetRef, "--right", ghostRef, "--reason", "OTK CLI happy path")
	if r.Exit != 0 {
		return fmt.Errorf("entity CLI lane: lm entity assert-same failed (exit %d): %s%s", r.Exit, r.Stdout, r.Stderr)
	}
	if !strings.Contains(r.Stdout, "recorded:") {
		entityCliFinding(st, fmt.Sprintf(
			"lm entity assert-same exited 0 but its stdout did not confirm the recording: %q", r.Stdout))
	}

	r = runner.Run(lmEnv(), lmBinary(), "entity", "assert-distinct", resEntityName,
		"--left", target2Ref, "--right", ghostDistinctRef, "--reason", "OTK CLI happy path")
	if r.Exit != 0 {
		return fmt.Errorf("entity CLI lane: lm entity assert-distinct failed (exit %d): %s%s", r.Exit, r.Stdout, r.Stderr)
	}
	if !strings.Contains(r.Stdout, "recorded:") {
		entityCliFinding(st, fmt.Sprintf(
			"lm entity assert-distinct exited 0 but its stdout did not confirm the recording: %q", r.Stdout))
	}

	r = runner.Run(lmEnv(), lmBinary(), "entity", "resolve", resEntityName)
	if r.Exit != 0 {
		return fmt.Errorf("entity CLI lane: second lm entity resolve failed (exit %d): %s%s", r.Exit, r.Stdout, r.Stderr)
	}
	return nil
}

// entityCliThenCleared checks that lm entity ambiguous no longer lists either fixture row after
// its assertion and the replay — the CLI-driven equivalent of resThenSurvives/resThenAmbiguous.
func entityCliThenCleared(st *scenarioState) error {
	if st.entityCliDormant {
		return nil
	}
	rows, err := entityCliRunAmbiguous()
	if err != nil {
		return err
	}
	if row := findRow(rows, st.entityCliTargetID); row != nil {
		entityCliFinding(st, fmt.Sprintf(
			"%q is still listed by lm entity ambiguous after lm entity assert-same and a replay — "+
				"the assertion did not clear it", st.entityCliTargetID))
	}
	if row := findRow(rows, st.entityCliTarget2ID); row != nil {
		entityCliFinding(st, fmt.Sprintf(
			"%q is still listed by lm entity ambiguous after lm entity assert-distinct and a replay — "+
				"the assertion did not clear it", st.entityCliTarget2ID))
	}
	return nil
}

// entityCliCheckHistory checks one fixture row's lm entity history output: the latest event must
// be decided_by=asserted, outcome=resolved, and (the veto's whole point) must name expectEntity —
// not just any resolution, but specifically the one the assertion decided.
func entityCliCheckHistory(st *scenarioState, sourceID, expectEntity, doorName string) error {
	r := runner.Run(lmEnv(), lmBinary(), "entity", "history", resEntityName, resTableB, sourceID, "-o", "json")
	if r.Exit != 0 {
		return fmt.Errorf("entity CLI lane: lm entity history failed (exit %d): %s%s", r.Exit, r.Stdout, r.Stderr)
	}
	var events []historyEvent
	if err := json.Unmarshal([]byte(r.Stdout), &events); err != nil {
		return fmt.Errorf("entity CLI lane: could not parse lm entity history output: %w", err)
	}
	if len(events) == 0 {
		entityCliFinding(st, fmt.Sprintf("lm entity history returned no events for %q after %s", sourceID, doorName))
		return nil
	}
	latest := events[len(events)-1] // evidenceHistory orders oldest first
	if !strings.EqualFold(latest.DecidedBy, "asserted") {
		entityCliFinding(st, fmt.Sprintf(
			"%q's latest lm entity history event is decided_by=%q after %s and a replay — want \"asserted\"",
			sourceID, latest.DecidedBy, doorName))
	}
	if !strings.EqualFold(latest.Outcome, "resolved") {
		entityCliFinding(st, fmt.Sprintf(
			"%q's latest lm entity history event is outcome=%q after %s — want \"resolved\"",
			sourceID, latest.Outcome, doorName))
	}
	found := false
	for _, c := range latest.Candidates {
		if strings.EqualFold(c, expectEntity) {
			found = true
		}
	}
	if !found {
		entityCliFinding(st, fmt.Sprintf(
			"%q's latest lm entity history event does not name %q among candidates=%v after %s — "+
				"the assertion's OWN decision must be the one recorded, not merely some resolution",
			sourceID, expectEntity, latest.Candidates, doorName))
	}
	return nil
}

// entityCliThenHistory checks that lm entity history shows each assertion, not just its effect —
// the fifth CLI door, and the proof that both corrections are auditable, not merely applied.
func entityCliThenHistory(st *scenarioState) error {
	if st.entityCliDormant {
		return nil
	}
	aliceEntity, err := resEntityID(resTable, st.entityCliGhostID)
	if err != nil {
		return fmt.Errorf("entity CLI lane: %w", err)
	}
	carolEntity, err := resEntityID(resTable, st.entityCliGhostCarol)
	if err != nil {
		return fmt.Errorf("entity CLI lane: %w", err)
	}
	if err := entityCliCheckHistory(st, st.entityCliTargetID, aliceEntity, "lm entity assert-same"); err != nil {
		return err
	}
	return entityCliCheckHistory(st, st.entityCliTarget2ID, carolEntity, "lm entity assert-distinct")
}
