package oidc

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Discover reads the OIDC discovery document at <issuer>/.well-known/openid-configuration
// and returns the device-authorization + token endpoints, so lm login needs only the issuer.
func TestDiscoverReadsWellKnown(t *testing.T) {
	var gotPath string
	var base string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_, _ = w.Write([]byte(`{
			"issuer": "` + base + `/realms/lumnik",
			"device_authorization_endpoint": "https://idp.example/dev",
			"token_endpoint": "https://idp.example/tok"
		}`))
	}))
	defer srv.Close()
	base = srv.URL

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
	var base string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_, _ = w.Write([]byte(`{"issuer":"` + base + `","device_authorization_endpoint":"d","token_endpoint":"t"}`))
	}))
	defer srv.Close()
	base = srv.URL

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

// The discovery document must name the issuer it was fetched from (trailing "/" aside), or its endpoints are
// not trusted (#2004).
func TestDiscoverRefusesAnotherIssuer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"issuer":"https://evil.example/realms/x","device_authorization_endpoint":"d","token_endpoint":"t"}`))
	}))
	defer srv.Close()
	_, err := Discover(context.Background(), http.DefaultClient, srv.URL+"/realms/x")
	if err == nil || !strings.Contains(err.Error(), "https://evil.example/realms/x") {
		t.Fatalf("err = %v", err)
	}
}

func TestDiscoverAcceptsIssuerWithTrailingSlash(t *testing.T) {
	var base string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"issuer":"` + base + `/realms/x/","device_authorization_endpoint":"d","token_endpoint":"t"}`))
	}))
	defer srv.Close()
	base = srv.URL
	if _, err := Discover(context.Background(), http.DefaultClient, srv.URL+"/realms/x"); err != nil {
		t.Fatal(err)
	}
}
