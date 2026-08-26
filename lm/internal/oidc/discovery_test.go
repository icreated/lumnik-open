package oidc

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// Discover reads the OIDC discovery document at <issuer>/.well-known/openid-configuration
// and returns the device-authorization + token endpoints, so lm login needs only the issuer.
func TestDiscoverReadsWellKnown(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_, _ = w.Write([]byte(`{
			"device_authorization_endpoint": "https://idp.example/dev",
			"token_endpoint": "https://idp.example/tok"
		}`))
	}))
	defer srv.Close()

	d, err := Discover(context.Background(), http.DefaultClient, srv.URL+"/realms/lumnik")
	if err != nil {
		t.Fatal(err)
	}
	if gotPath != "/realms/lumnik/.well-known/openid-configuration" {
		t.Errorf("wrong discovery path: %s", gotPath)
	}
	if d.DeviceAuthorizationEndpoint != "https://idp.example/dev" {
		t.Errorf("device endpoint: %q", d.DeviceAuthorizationEndpoint)
	}
	if d.TokenEndpoint != "https://idp.example/tok" {
		t.Errorf("token endpoint: %q", d.TokenEndpoint)
	}
}

// A trailing slash on the issuer must not double up in the discovery URL.
func TestDiscoverTrimsTrailingSlash(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_, _ = w.Write([]byte(`{"device_authorization_endpoint":"d","token_endpoint":"t"}`))
	}))
	defer srv.Close()

	if _, err := Discover(context.Background(), http.DefaultClient, srv.URL+"/"); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/.well-known/openid-configuration" {
		t.Errorf("trailing slash not trimmed: %s", gotPath)
	}
}

// A provider that omits the device endpoint can't drive the device flow — say so clearly.
func TestDiscoverErrorsWhenNoDeviceEndpoint(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"token_endpoint":"t"}`))
	}))
	defer srv.Close()

	if _, err := Discover(context.Background(), http.DefaultClient, srv.URL); err == nil {
		t.Error("expected an error when device_authorization_endpoint is absent")
	}
}
