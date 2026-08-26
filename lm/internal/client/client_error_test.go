package client

import (
	"strings"
	"testing"
)

func TestAPIErrorRendersJSONErrorBody(t *testing.T) {
	e := &APIError{Status: 400, Body: `{"error":"metadata.name must be a URL-safe slug"}`}
	got := e.Error()
	if want := "API error 400: metadata.name must be a URL-safe slug"; got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestAPIErrorScopeRefusal(t *testing.T) {
	e := &APIError{Status: 403, Body: `{"error":"scope not granted"}`}
	if got, want := e.Error(), "access refused (403): scope not granted"; got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestAPIErrorRateLimited(t *testing.T) {
	e := &APIError{Status: 429, Body: `{"error":"rate limit exceeded"}`, RetryAfter: "37"}
	if got, want := e.Error(), "rate limited (429): rate limit exceeded, retry in 37s"; got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
	e2 := &APIError{Status: 429, Body: `{"error":"rate limit exceeded"}`}
	if got, want := e2.Error(), "rate limited (429): rate limit exceeded"; got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestAPIErrorNonJSONBodyUnchanged(t *testing.T) {
	e := &APIError{Status: 500, Body: "boom"}
	if got := e.Error(); !strings.Contains(got, "API error 500: boom") {
		t.Fatalf("got %q", got)
	}
}

func TestAPIErrorEmptyBodyHintsUnchanged(t *testing.T) {
	e := &APIError{Status: 403}
	if got := e.Error(); !strings.Contains(got, "your account may lack the required role") {
		t.Fatalf("got %q", got)
	}
}
