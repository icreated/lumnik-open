package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestKeycloak_Non200_IsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(401)
		_, _ = w.Write([]byte(`{"error":"invalid_grant"}`))
	}))
	defer srv.Close()
	k := Keycloak{TokenURL: srv.URL, ClientID: "lm-cli", Username: "u", Password: "p"}
	if _, err := k.Integrator("1", "1"); err == nil {
		t.Fatal("non-200 from token endpoint must error")
	}
}

func TestKeycloak_EmptyToken_IsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		_, _ = w.Write([]byte(`{"access_token":""}`))
	}))
	defer srv.Close()
	k := Keycloak{TokenURL: srv.URL, ClientID: "lm-cli", Username: "u", Password: "p"}
	if _, err := k.Integrator("1", "1"); err == nil {
		t.Fatal("empty access_token must error")
	}
}

func TestKeycloak_HappyPath_ReturnsToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.PostForm.Get("grant_type") != "password" {
			w.WriteHeader(400)
			return
		}
		w.WriteHeader(200)
		_, _ = w.Write([]byte(`{"access_token":"real-token-123"}`))
	}))
	defer srv.Close()
	k := Keycloak{TokenURL: srv.URL, ClientID: "lm-cli", Username: "u", Password: "p"}
	tok, err := k.Integrator("1", "1")
	if err != nil || tok != "real-token-123" {
		t.Fatalf("happy path: got (%q,%v), want (real-token-123, nil)", tok, err)
	}
}
