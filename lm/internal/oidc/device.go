// Package oidc implements the RFC 8628 device authorization grant for lm login.
package oidc

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type DeviceCode struct {
	DeviceCode              string `json:"device_code"`
	UserCode                string `json:"user_code"`
	VerificationURI         string `json:"verification_uri"`
	VerificationURIComplete string `json:"verification_uri_complete"`
	ExpiresIn               int    `json:"expires_in"`
	Interval                int    `json:"interval"`
}

type Token struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
}

func postForm(ctx context.Context, c *http.Client, endpoint string, form url.Values) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, nil, fmt.Errorf("read response body: %w", err)
	}
	return resp.StatusCode, b, nil
}

// RequestDeviceCode starts the device flow at the device-authorization endpoint.
func RequestDeviceCode(c *http.Client, deviceEndpoint, clientID string) (*DeviceCode, error) {
	status, body, err := postForm(context.Background(), c, deviceEndpoint, url.Values{"client_id": {clientID}, "scope": {"openid"}})
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("device-authorization failed (%d): %s", status, body)
	}
	var d DeviceCode
	if err := json.Unmarshal(body, &d); err != nil {
		return nil, fmt.Errorf("parse device response: %w", err)
	}
	if d.Interval <= 0 {
		d.Interval = 5
	}
	return &d, nil
}

// PollToken polls the token endpoint until the user authenticates, slowing on
// slow_down and erroring on any non-pending error. It is bounded by ctx: when the
// caller's context is cancelled or its deadline (typically the device-code expiry)
// passes, it returns a clear timeout error instead of looping forever.
func PollToken(ctx context.Context, c *http.Client, tokenEndpoint, clientID, deviceCode string, interval time.Duration) (*Token, error) {
	form := url.Values{
		"grant_type":  {"urn:ietf:params:oauth:grant-type:device_code"},
		"device_code": {deviceCode},
		"client_id":   {clientID},
	}
	for {
		if err := ctx.Err(); err != nil {
			return nil, fmt.Errorf("device authorization timed out before completion: %w", err)
		}
		status, body, err := postForm(ctx, c, tokenEndpoint, form)
		if err != nil {
			// The deadline can expire while the POST is in flight — the transport then
			// reports its own wrapped error. That is still OUR timeout, not a server
			// failure: classify it, same phrasing as the two checks around the loop.
			if ctx.Err() != nil {
				return nil, fmt.Errorf("device authorization timed out before completion: %w", ctx.Err())
			}
			return nil, err
		}
		if status < 400 {
			var t Token
			if err := json.Unmarshal(body, &t); err != nil {
				return nil, fmt.Errorf("parse token response: %w", err)
			}
			return &t, nil
		}
		var e struct {
			Error string `json:"error"`
		}
		_ = json.Unmarshal(body, &e)
		switch e.Error {
		case "authorization_pending":
			// wait one interval
		case "slow_down":
			interval += 5 * time.Second
		default:
			return nil, fmt.Errorf("device token error: %s", strings.TrimSpace(e.Error))
		}
		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("device authorization timed out before completion: %w", ctx.Err())
		case <-time.After(interval):
		}
	}
}

// Refresh exchanges a refresh token for a new access/refresh pair.
func Refresh(c *http.Client, tokenEndpoint, clientID, refreshToken string) (*Token, error) {
	status, body, err := postForm(context.Background(), c, tokenEndpoint, url.Values{
		"grant_type":    {"refresh_token"},
		"refresh_token": {refreshToken},
		"client_id":     {clientID},
	})
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("refresh failed (%d): %s", status, body)
	}
	var t Token
	if err := json.Unmarshal(body, &t); err != nil {
		return nil, fmt.Errorf("parse refresh response: %w", err)
	}
	return &t, nil
}
