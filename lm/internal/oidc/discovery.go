package oidc

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// Discovery holds the endpoints lm login needs from an OIDC provider's
// .well-known/openid-configuration document.
type Discovery struct {
	DeviceAuthorizationEndpoint string `json:"device_authorization_endpoint"`
	TokenEndpoint               string `json:"token_endpoint"`
}

// Discover fetches <issuer>/.well-known/openid-configuration and returns the
// device-authorization + token endpoints — so a context needs only the issuer URL,
// not the individual endpoints hand-copied from Keycloak. The ctx lets the caller
// abort (Ctrl-C) a slow discovery.
func Discover(ctx context.Context, c *http.Client, issuer string) (*Discovery, error) {
	url := strings.TrimRight(issuer, "/") + "/.well-known/openid-configuration"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch OIDC discovery from %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("OIDC discovery failed (%s) at %s", resp.Status, url)
	}
	var d Discovery
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		return nil, fmt.Errorf("parse OIDC discovery: %w", err)
	}
	if d.DeviceAuthorizationEndpoint == "" {
		return nil, fmt.Errorf("provider at %s advertises no device_authorization_endpoint — device flow unsupported", issuer)
	}
	if d.TokenEndpoint == "" {
		return nil, fmt.Errorf("provider at %s advertises no token_endpoint", issuer)
	}
	return &d, nil
}
