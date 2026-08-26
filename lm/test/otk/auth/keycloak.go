package auth

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Keycloak obtains real access tokens via the OAuth2 direct-grant (password) flow.
// E2E-only: it never forges invalid tokens (Keycloak issues valid ones); the
// smoke's negative case is transport-level (no Authorization header).
type Keycloak struct {
	TokenURL string // e.g. http://localhost:8180/realms/lumnik/protocol/openid-connect/token
	ClientID string
	Username string
	Password string
}

var _ TokenSource = Keycloak{}

func (k Keycloak) Integrator(tenant, user string) (string, error) {
	form := url.Values{
		"grant_type": {"password"},
		"client_id":  {k.ClientID},
		"username":   {k.Username},
		"password":   {k.Password},
		"scope":      {"openid"},
	}
	cl := &http.Client{Timeout: 15 * time.Second}
	resp, err := cl.Post(k.TokenURL, "application/x-www-form-urlencoded", strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return "", errors.New("keycloak token endpoint: " + resp.Status + ": " + string(b))
	}
	var out struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.Unmarshal(b, &out); err != nil {
		return "", err
	}
	if out.AccessToken == "" {
		return "", errors.New("keycloak returned an empty access_token")
	}
	return out.AccessToken, nil
}
