package resources

import (
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

func TestFriendlyAskError_403_ScopeRefusal(t *testing.T) {
	// 403 with body — scope refusal should return the (now honest) APIError message
	// and NOT the schema-discovery hint (which is wrong for scope issues).
	msg := friendlyAskError(
		&client.APIError{Status: 403, Body: `{"error":"scope not granted"}`},
		"atelier",
	)
	// Must contain the exact honest rendering from APIError.Error()
	if !strings.Contains(msg, "access refused (403)") {
		t.Errorf("403 msg = %q, want 'access refused (403)'", msg)
	}
	if !strings.Contains(msg, "scope not granted") {
		t.Errorf("403 msg = %q, want 'scope not granted'", msg)
	}
	// Must NOT contain the schema-discovery hint (which would be a lie)
	if strings.Contains(msg, "schema") || strings.Contains(msg, "Hint") {
		t.Errorf("403 msg = %q, must NOT contain 'schema' or 'Hint'", msg)
	}
}

func TestFriendlyAskError_429_RateLimit(t *testing.T) {
	// 429 with body and Retry-After — rate limit should return the (now honest) APIError message
	// and NOT the schema-discovery hint (which is wrong for rate-limit issues).
	msg := friendlyAskError(
		&client.APIError{Status: 429, Body: `{"error":"rate limit exceeded"}`, RetryAfter: "12"},
		"atelier",
	)
	// Must contain the exact honest rendering from APIError.Error()
	if !strings.Contains(msg, "rate limited (429)") {
		t.Errorf("429 msg = %q, want 'rate limited (429)'", msg)
	}
	if !strings.Contains(msg, "rate limit exceeded") {
		t.Errorf("429 msg = %q, want 'rate limit exceeded'", msg)
	}
	if !strings.Contains(msg, "retry in 12s") {
		t.Errorf("429 msg = %q, want 'retry in 12s'", msg)
	}
	// Must NOT contain the schema-discovery hint (which would be a lie)
	if strings.Contains(msg, "schema") || strings.Contains(msg, "Hint") {
		t.Errorf("429 msg = %q, must NOT contain 'schema' or 'Hint'", msg)
	}
}

func TestFriendlyAskError_500_SchemaMissing(t *testing.T) {
	// 500 with body — when it IS a real error, return the error FIRST + hint labeled as hypothesis
	msg := friendlyAskError(
		&client.APIError{Status: 500, Body: `{"error":"boom"}`},
		"atelier",
	)
	// Must contain the actual error first
	if !strings.Contains(msg, "API error 500") || !strings.Contains(msg, "boom") {
		t.Errorf("500 msg = %q, want 'API error 500' and 'boom'", msg)
	}
	// Must contain the hint about schema, clearly labeled as such
	if !strings.Contains(msg, "Hint:") {
		t.Errorf("500 msg = %q, want 'Hint:'", msg)
	}
	if !strings.Contains(msg, "schema") {
		t.Errorf("500 msg = %q, want 'schema'", msg)
	}
	// The hint names the scope so it stays actionable when several scopes exist.
	if !strings.Contains(msg, "«atelier»") {
		t.Errorf("500 msg = %q, want the scope named in the hint", msg)
	}
}
