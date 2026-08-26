//go:build otk

package otk

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/cucumber/godog"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// The saved-view store round-trip (#140): apply a valid manifest, discover it
// via GET /api/views, fetch its YAML via GET /api/views/{name}, and render it —
// plus the apply-refusals a reserved name and an unknown scope must produce
// before any row is read. Also pins the uniform scope-403 contract (#131/#138):
// a scope-bound identity lacking the manifest's scope gets exactly
// 403 {"error":"scope not granted"} — the same refusal every scoped door on
// the app surface gives (chat, views, entities alike).
const (
	stepStoreGiven        = `^a store manifest for variant "([^"]*)"$`
	stepStoreApply        = `^I apply the view manifest to the store$`
	stepStoreApplyStatus  = `^the view apply response status is (\d+)$`
	stepStoreInList       = `^the saved view "([^"]*)" appears in the list$`
	stepStoreGetYAML      = `^getting the saved view "([^"]*)" returns its YAML$`
	stepStoreRenderPlain  = `^I render the applied view$`
	stepStoreRenderStatus = `^the applied-view render response status is (\d+)$`
	stepStoreScopeBound   = `^a scope-bound identity lacking scope "([^"]*)" renders the manifest$`
	stepStoreScopeRefusal = `^the response is the uniform scope refusal$`
)

func init() {
	stepPatterns = append(stepPatterns,
		stepStoreGiven, stepStoreApply, stepStoreApplyStatus, stepStoreInList,
		stepStoreGetYAML, stepStoreRenderPlain, stepStoreRenderStatus,
		stepStoreScopeBound, stepStoreScopeRefusal)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepStoreGiven, func(variant string) error { return storeGiven(st, variant) })
		ctx.Step(stepStoreApply, func() error { return storeApply(st) })
		ctx.Step(stepStoreApplyStatus, func(code int) error { return storeApplyStatus(st, code) })
		ctx.Step(stepStoreInList, func(name string) error { return storeInList(st, name) })
		ctx.Step(stepStoreGetYAML, func(name string) error { return storeGetYAML(st, name) })
		ctx.Step(stepStoreRenderPlain, func() error { return storeRenderPlain(st) })
		ctx.Step(stepStoreRenderStatus, func(code int) error { return storeRenderStatusStep(st, code) })
		ctx.Step(stepStoreScopeBound, func(scope string) error { return storeScopeBoundRender(st, scope) })
		ctx.Step(stepStoreScopeRefusal, func() error { return storeScopeRefusal(st) })
	})
}

func storeGiven(st *scenarioState, variant string) error {
	manifest, found := generator.StoreVariantByName(variant)
	if !found {
		return fmt.Errorf("view-store lane: unknown variant %q", variant)
	}
	st.storeVariant = variant
	st.storeManifest = manifest
	return nil
}

// storeNeedsCard reports whether the variant resolves against the seeded otk
// SchemaCard. Only the round-trip (a real apply + render) needs it — the two
// refusals are decided before any card lookup (name validation / scope-grant
// check / an always-absent ghost-scope card).
func storeNeedsCard(variant string) bool {
	return variant == "store-roundtrip"
}

func storeApply(st *scenarioState) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("view-store lane: LUMNIK_URL is not set")
	}
	if storeNeedsCard(st.storeVariant) && seedDSNAbsent() {
		fmt.Printf("  OTK view-store lane DORMANT (variant=%q needs the seeded otk card): set OTK_DB_DSN\n", st.storeVariant)
		st.storeApplyStatus = -1
		return nil
	}
	token, err := currentToken() // apply is an lm_integrator door
	if err != nil {
		return fmt.Errorf("view-store lane: mint integrator token: %w", err)
	}
	status, body, err := inboxHTTPBody("POST", "/api/views/apply", token, []byte(st.storeManifest), "application/yaml")
	if err != nil {
		return fmt.Errorf("view-store lane: POST /api/views/apply: %w", err)
	}
	st.storeApplyStatus = status
	st.storeApplyBody = body
	return nil
}

func storeApplyStatus(st *scenarioState, code int) error {
	if st.storeApplyStatus == -1 {
		return nil // dormant
	}
	if st.storeApplyStatus == 0 {
		return fmt.Errorf("view-store lane: apply was never called (status=0)")
	}
	if st.storeApplyStatus != code {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Security,
			Detail: fmt.Sprintf("view-store apply status: got %d (body=%q), want %d", st.storeApplyStatus, st.storeApplyBody, code),
		})
	}
	return nil
}

func storeInList(st *scenarioState, name string) error {
	if st.storeApplyStatus == -1 {
		return nil // dormant
	}
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("view-store lane: mint token: %w", err)
	}
	status, body, err := inboxHTTPBody("GET", "/api/views", token, nil, "")
	if err != nil {
		return fmt.Errorf("view-store lane: GET /api/views: %w", err)
	}
	if status != 200 {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Robustness,
			Detail: fmt.Sprintf("saved-view list status: got %d, want 200", status),
		})
		return nil
	}
	var rows []struct {
		Name string `json:"name"`
	}
	if err := json.Unmarshal([]byte(body), &rows); err != nil {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Robustness,
			Detail: fmt.Sprintf("saved-view list is not a JSON array: %v", err),
		})
		return nil
	}
	for _, r := range rows {
		if r.Name == name {
			return nil
		}
	}
	st.findings = append(st.findings, oracle.Finding{
		Severity: oracle.High, Family: oracle.Robustness,
		Detail: fmt.Sprintf("saved view %q not found via GET /api/views after apply", name),
	})
	return nil
}

func storeGetYAML(st *scenarioState, name string) error {
	if st.storeApplyStatus == -1 {
		return nil // dormant
	}
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("view-store lane: mint token: %w", err)
	}
	status, body, err := storeGetManifest(name, token)
	if err != nil {
		return fmt.Errorf("view-store lane: GET /api/views/%s: %w", name, err)
	}
	if status != 200 || !strings.Contains(body, "kind: View") {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Robustness,
			Detail: fmt.Sprintf("GET /api/views/%s: status=%d body=%q (want 200 + YAML containing 'kind: View')", name, status, body),
		})
	}
	return nil
}

func storeRenderPlain(st *scenarioState) error {
	if st.storeApplyStatus == -1 {
		st.storeRenderStatus = -1
		return nil // dormant
	}
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("view-store lane: mint token: %w", err)
	}
	status, body, err := inboxHTTPBody("POST", "/api/views/render", token, []byte(st.storeManifest), "application/yaml")
	if err != nil {
		return fmt.Errorf("view-store lane: POST /api/views/render: %w", err)
	}
	st.storeRenderStatus = status
	st.storeRenderBody = body
	return nil
}

func storeRenderStatusStep(st *scenarioState, code int) error {
	if st.storeRenderStatus == -1 {
		return nil // dormant
	}
	if st.storeRenderStatus == 0 {
		return fmt.Errorf("view-store lane: render was never called (status=0)")
	}
	if st.storeRenderStatus != code {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Security,
			Detail: fmt.Sprintf("view-store render status: got %d, want %d", st.storeRenderStatus, code),
		})
	}
	return nil
}

// storeScopeBoundRender forges a valid identity bound to a scope OTHER than the
// one named (e.g. "otk") and renders the given manifest with it — pinning the
// uniform scope-403 refusal without requiring the "otk" card to be seeded (the
// scope-grant check in ViewRenderResource runs BEFORE the SchemaCard lookup).
func storeScopeBoundRender(st *scenarioState, scope string) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("view-store lane: LUMNIK_URL is not set")
	}
	if otkKey == nil {
		fmt.Printf("  OTK view-store lane DORMANT (scope-403 pin): set OTK_OIDC_ISSUER + boot %%otkauth\n")
		st.storeRenderStatus = -1
		return nil
	}
	token, err := otkKey.ScopeBound(otkTenant, otkTenant, "not-"+scope)
	if err != nil {
		return fmt.Errorf("view-store lane: forge scope-bound token: %w", err)
	}
	status, body, err := inboxHTTPBody("POST", "/api/views/render", token, []byte(st.storeManifest), "application/yaml")
	if err != nil {
		return fmt.Errorf("view-store lane: POST /api/views/render: %w", err)
	}
	st.storeRenderStatus = status
	st.storeRenderBody = body
	return nil
}

// storeScopeRefusal asserts the uniform shape: exactly 403 and a body that is
// ONLY {"error":"scope not granted"} — the same refusal chat, views, and
// entities all give (CLAUDE.md: "All refusals are uniform").
func storeScopeRefusal(st *scenarioState) error {
	if st.storeRenderStatus == -1 {
		return nil // dormant
	}
	if st.storeRenderStatus != 403 {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Critical, Family: oracle.Security,
			Detail: fmt.Sprintf("scope-bound render: got status %d, want 403 (scope not granted)", st.storeRenderStatus),
		})
		return nil
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(st.storeRenderBody), &body); err != nil {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Robustness,
			Detail: fmt.Sprintf("scope-403 body is not JSON: %v (body=%q)", err, st.storeRenderBody),
		})
		return nil
	}
	if len(body) != 1 || body["error"] != "scope not granted" {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Medium, Family: oracle.Security,
			Detail: fmt.Sprintf("scope-403 body shape drifted from the uniform refusal: %v", body),
		})
	}
	return nil
}

// storeGetManifest fetches a saved view's manifest with the SAME contract lm's own
// Client.ViewGet uses: path-escaped name segment and a yaml-first Accept — the
// shared inboxHTTPBody helper hard-codes Accept: application/json, which would
// negotiate the wrong type (or 406) against the endpoint's application/yaml body.
func storeGetManifest(name, token string) (int, string, error) {
	req, err := http.NewRequest("GET", entityBaseURL()+"/api/views/"+url.PathEscape(name), nil)
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("Accept", "application/yaml, application/json;q=0.9")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	// Same OTK conventions as inboxHTTPBody: dev-bypass resolves tenant/user from
	// these headers, and the shared client carries the harness timeout.
	req.Header.Set("X-Tenant-ID", otkTenant)
	req.Header.Set("X-User-ID", otkTenant)
	resp, err := entityHTTPClient().Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, "", err
	}
	return resp.StatusCode, string(data), nil
}
