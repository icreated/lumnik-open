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
)

func init() {
	stepPatterns = append(stepPatterns,
		stepEntityGivenApply, stepEntityWhenApply, stepEntityNotStatus, stepEntityExactStatus,
		stepEntityGivenRead, stepEntityWhenRead, stepEntityReadStatus, stepEntityReadSafe,
		stepEntityReadRows, stepEntityNoRows, stepEntityCleanup)
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
	yamlStr, useUserRole, _, found := generator.EntityApplyManifest(variant)
	if !found {
		return fmt.Errorf("entity lane: unknown apply variant %q", variant)
	}
	st.entityScen = generator.EntityScenario{
		Seed:         parseSeed(seedStr),
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
			fmt.Printf("  OTK entity lane DORMANT (wrong-role-apply): set OTK_OIDC_ISSUER + boot %%otkauth\n")
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

	var token string
	if crossTenant {
		if otkKey == nil {
			fmt.Printf("  OTK entity lane DORMANT (cross-tenant-read): set OTK_OIDC_ISSUER + boot %%otkauth\n")
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
			fmt.Printf("  OTK entity read lane DORMANT (variant=%q): set OTK_DB_DSN to seed test entities\n", name)
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
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity read response is not a JSON array: %v", err),
		})
		return nil
	}
	if len(rows) > max {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Low,
			Family:   oracle.Robustness,
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
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("entity read (cross-tenant) response is not a JSON array: %v", err),
		})
		return nil
	}
	if len(rows) != 0 {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Critical,
			Family:   oracle.Security,
			Seed:     st.entityScen.Seed,
			Detail:   fmt.Sprintf("cross-tenant entity read returned %d rows (RLS isolation breach)", len(rows)),
		})
	}
	return nil
}
