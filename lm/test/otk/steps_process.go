//go:build otk

package otk

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"github.com/cucumber/godog"
	otkauth "github.com/icreated/lumnik-open/lm/test/otk/auth"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

const (
	stepProcGivenApply   = `^a process manifest for variant "([^"]*)"(?: \[seed=(-?\d+)\])?$`
	stepProcWhenApply    = `^I apply the process manifest as (lm_admin|lm_user)$`
	stepProcApplyStatus  = `^the process apply response status is (\d+)$`
	stepProcApplyNot500  = `^the process apply response status is not 500$`
	stepProcApplyCleanup = `^wrong-role-process-apply must not return 200$`
	stepInboxGiven       = `^the inbox variant "([^"]*)"(?: \[seed=(-?\d+)\])?$`
	stepInboxWhenReadQ   = `^I read the inbox with query "([^"]*)"( as cross-tenant| as viewer)?$`
	stepInboxWhenRead    = `^I read the inbox( as cross-tenant| as viewer)?$`
	stepInboxWhenAck     = `^I ack inbox id "([^"]*)"( as cross-tenant| as viewer)?$`
	stepInboxReadStatus  = `^the inbox response status is (\d+)$`
	stepInboxRows        = `^the inbox response has at most (\d+) rows$`
	stepInboxSafeArray   = `^the inbox response is a safe array$`
	stepInboxAckStatus   = `^the inbox ack response status is (\d+)$`
)

func init() {
	stepPatterns = append(stepPatterns,
		stepProcGivenApply, stepProcWhenApply, stepProcApplyStatus, stepProcApplyNot500,
		stepProcApplyCleanup, stepInboxGiven, stepInboxWhenReadQ, stepInboxWhenRead,
		stepInboxWhenAck, stepInboxReadStatus, stepInboxRows, stepInboxSafeArray, stepInboxAckStatus)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepProcGivenApply, func(variant, seedStr string) error { return procGivenApply(st, variant, seedStr) })
		ctx.Step(stepProcWhenApply, func(role string) error { return procWhenApply(st, role) })
		ctx.Step(stepProcApplyStatus, func(code int) error { return procApplyStatus(st, code) })
		ctx.Step(stepProcApplyNot500, func() error { return procApplyStatus(st, -500) })
		ctx.Step(stepProcApplyCleanup, func() error { return procWrongRoleCleanup(st) })
		ctx.Step(stepInboxGiven, func(variant, seedStr string) error { return inboxGiven(st, variant, seedStr) })
		ctx.Step(stepInboxWhenReadQ, func(query, suffix string) error { return inboxWhenRead(st, query, suffix) })
		ctx.Step(stepInboxWhenRead, func(suffix string) error { return inboxWhenRead(st, "", suffix) })
		ctx.Step(stepInboxWhenAck, func(id, suffix string) error { return inboxWhenAck(st, id, suffix) })
		ctx.Step(stepInboxReadStatus, func(code int) error { return inboxStatusStep(st, code, false) })
		ctx.Step(stepInboxRows, func(max int) error { return inboxRows(st, max) })
		ctx.Step(stepInboxSafeArray, func() error { return inboxSafeArray(st) })
		ctx.Step(stepInboxAckStatus, func(code int) error { return inboxStatusStep(st, code, true) })
	})
}

// ---- process apply ----

func procGivenApply(st *scenarioState, variant, seedStr string) error {
	yamlStr, useUserRole, _, found := generator.ProcessApplyManifest(variant)
	if !found {
		return fmt.Errorf("process lane: unknown apply variant %q", variant)
	}
	st.processScen = generator.ProcessScenario{
		Seed:         parseSeed(seedStr),
		SubType:      "apply",
		ManifestYAML: yamlStr,
		Variant:      variant,
		UseUserRole:  useUserRole,
	}
	return nil
}

func procWhenApply(st *scenarioState, role string) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("process lane: LUMNIK_URL is not set")
	}
	var token string
	if role == "lm_user" {
		// Wrong-role apply needs a real forged viewer token; dormant under dev-bypass.
		if otkKey == nil {
			fmt.Printf("  OTK process lane DORMANT (wrong-role-apply): set OTK_OIDC_ISSUER + boot %%otkauth\n")
			st.processApplyStatus = -1
			return nil
		}
		var err error
		if token, err = otkKey.Forge(otkauth.WrongRole, 0); err != nil {
			return fmt.Errorf("process lane: forge wrong-role token: %w", err)
		}
	} else {
		var err error
		if token, err = currentToken(); err != nil {
			return fmt.Errorf("process lane: mint integrator token: %w", err)
		}
	}
	status, _, err := processApplyHTTP([]byte(st.processScen.ManifestYAML), token)
	if err != nil {
		return fmt.Errorf("process lane: POST /api/processes/apply: %w", err)
	}
	st.processApplyStatus = status
	return nil
}

// procApplyStatus asserts an exact status, or (code == -500) that the status is not 500.
func procApplyStatus(st *scenarioState, code int) error {
	if st.processApplyStatus == -1 {
		return nil // dormant
	}
	if st.processApplyStatus == 0 {
		return fmt.Errorf("process lane: apply was never called (status=0)")
	}
	if code == -500 {
		if st.processApplyStatus == 500 {
			st.findings = append(st.findings, oracle.Finding{
				Severity: oracle.High, Family: oracle.Robustness, Seed: st.processScen.Seed,
				Detail: "process apply returned 500 (an adversarial manifest must be refused cleanly, never crash)",
			})
		}
		return nil
	}
	if st.processApplyStatus != code {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Security, Seed: st.processScen.Seed,
			Detail: fmt.Sprintf("process apply status: got %d, want %d", st.processApplyStatus, code),
		})
	}
	return nil
}

func procWrongRoleCleanup(st *scenarioState) error {
	if st.processApplyStatus == -1 {
		return nil // dormant
	}
	if st.processApplyStatus == 200 {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.Critical, Family: oracle.Security, Seed: st.processScen.Seed,
			Detail: "wrong-role process apply returned 200 (role escalation — must be 403)",
		})
	}
	return nil
}

// ---- inbox read + ack ----

func inboxGiven(st *scenarioState, variant, seedStr string) error {
	op, crossTenant, wrongRole, found := generator.InboxVariantByName(variant)
	if !found {
		return fmt.Errorf("process lane: unknown inbox variant %q", variant)
	}
	st.processScen = generator.ProcessScenario{
		Seed:        parseSeed(seedStr),
		SubType:     "inbox",
		Variant:     variant,
		InboxOp:     op,
		CrossTenant: crossTenant,
		WrongRole:   wrongRole,
	}
	return nil
}

// inboxToken resolves the token for the requested persona. Cross-tenant and viewer
// personas require real-auth (a forged token); they are dormant under dev-bypass.
// Returns ("", true) when the scenario is dormant.
func inboxToken(suffix string) (token string, dormant bool, err error) {
	switch strings.TrimSpace(suffix) {
	case "as cross-tenant":
		if otkKey == nil {
			return "", true, nil
		}
		token, err = otkKey.Forge(otkauth.CrossTenant, 0)
	case "as viewer":
		if otkKey == nil {
			return "", true, nil
		}
		token, err = otkKey.Forge(otkauth.WrongRole, 0)
	default:
		token, err = currentToken()
	}
	return token, false, err
}

func inboxWhenRead(st *scenarioState, query, suffix string) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("process lane: LUMNIK_URL is not set")
	}
	token, dormant, err := inboxToken(suffix)
	if err != nil {
		return fmt.Errorf("process lane: inbox token: %w", err)
	}
	if dormant {
		fmt.Printf("  OTK process inbox lane DORMANT (%s): set OTK_OIDC_ISSUER + boot %%otkauth\n", strings.TrimSpace(suffix))
		st.inboxStatus = -1
		return nil
	}
	path := "/api/inbox"
	if query != "" {
		path += "?" + query
	}
	status, body, err := inboxHTTP("GET", path, token)
	if err != nil {
		return fmt.Errorf("process lane: GET /api/inbox: %w", err)
	}
	st.inboxStatus = status
	st.inboxBody = body
	return nil
}

func inboxWhenAck(st *scenarioState, id, suffix string) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("process lane: LUMNIK_URL is not set")
	}
	token, dormant, err := inboxToken(suffix)
	if err != nil {
		return fmt.Errorf("process lane: inbox token: %w", err)
	}
	if dormant {
		fmt.Printf("  OTK process inbox lane DORMANT (ack %s): set OTK_OIDC_ISSUER + boot %%otkauth\n", strings.TrimSpace(suffix))
		st.inboxStatus = -1
		return nil
	}
	status, body, err := inboxHTTP("POST", "/api/inbox/"+id+"/ack", token)
	if err != nil {
		return fmt.Errorf("process lane: POST inbox ack: %w", err)
	}
	st.inboxStatus = status
	st.inboxBody = body
	return nil
}

func inboxStatusStep(st *scenarioState, code int, ack bool) error {
	if st.inboxStatus == -1 {
		return nil // dormant
	}
	if st.inboxStatus == 0 {
		return fmt.Errorf("process lane: inbox call was never made (status=0)")
	}
	if st.inboxStatus != code {
		kind := "inbox read"
		if ack {
			kind = "inbox ack"
		}
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Security, Seed: st.processScen.Seed,
			Detail: fmt.Sprintf("%s status: got %d, want %d", kind, st.inboxStatus, code),
		})
	}
	return nil
}

func inboxRows(st *scenarioState, max int) error {
	if st.inboxStatus == -1 {
		return nil // dormant
	}
	var rows []json.RawMessage
	if err := json.Unmarshal([]byte(st.inboxBody), &rows); err != nil {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Robustness, Seed: st.processScen.Seed,
			Detail: fmt.Sprintf("inbox response is not a JSON array: %v", err),
		})
		return nil
	}
	if len(rows) > max {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Security, Seed: st.processScen.Seed,
			Detail: fmt.Sprintf("inbox returned %d rows, exceeds the server clamp of %d (limit not enforced)", len(rows), max),
		})
	}
	return nil
}

func inboxSafeArray(st *scenarioState) error {
	if st.inboxStatus == -1 {
		return nil // dormant
	}
	var rows []json.RawMessage
	if err := json.Unmarshal([]byte(st.inboxBody), &rows); err != nil {
		st.findings = append(st.findings, oracle.Finding{
			Severity: oracle.High, Family: oracle.Robustness, Seed: st.processScen.Seed,
			Detail: fmt.Sprintf("inbox response is not a JSON array: %v", err),
		})
	}
	return nil
}

// ---- HTTP helpers ----

func processApplyHTTP(yamlBytes []byte, token string) (int, string, error) {
	return inboxHTTPBody("POST", "/api/processes/apply", token, yamlBytes, "application/yaml")
}

func inboxHTTP(method, path, token string) (int, string, error) {
	return inboxHTTPBody(method, path, token, nil, "")
}

func inboxHTTPBody(method, path, token string, body []byte, contentType string) (int, string, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, entityBaseURL()+path, reader)
	if err != nil {
		return 0, "", err
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	req.Header.Set("Accept", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("X-Tenant-ID", otkTenant)
	req.Header.Set("X-User-ID", otkTenant)
	resp, err := entityHTTPClient().Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b), nil
}
