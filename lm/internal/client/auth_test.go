package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
)

func TestBearerToken_validNotExpired(t *testing.T) {
	ctx := config.Context{Access: "AT", ExpiresAt: time.Now().Add(time.Hour).Unix()}
	got, refreshed, err := bearerToken(http.DefaultClient, "", ctx, time.Now())
	if err != nil || got != "AT" || refreshed != nil {
		t.Fatalf("valid token must pass through: got=%q refreshed=%v err=%v", got, refreshed, err)
	}
}

func TestBearerToken_expiredRefreshes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"NEW","refresh_token":"RT2","expires_in":300}`))
	}))
	defer srv.Close()
	ctx := config.Context{Access: "OLD", Refresh: "RT", ExpiresAt: time.Now().Add(-time.Minute).Unix(),
		OidcClientID: "lm-cli", OidcTokenEndpoint: srv.URL}
	got, refreshed, err := bearerToken(srv.Client(), srv.URL, ctx, time.Now())
	if err != nil || got != "NEW" {
		t.Fatalf("expired token must refresh: got=%q err=%v", got, err)
	}
	if refreshed == nil || refreshed.Access != "NEW" || refreshed.Refresh != "RT2" {
		t.Fatalf("refreshed context not returned for persistence: %+v", refreshed)
	}
}

func TestBearerToken_expiredMissingRefreshConfig_errorsWithoutHTTP(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("must NOT call the token endpoint when refresh config is incomplete")
	}))
	defer srv.Close()
	// expired, but no refresh token → must error clearly, never POST an empty refresh
	ctx := config.Context{Access: "OLD", Refresh: "", ExpiresAt: time.Now().Add(-time.Minute).Unix(),
		OidcClientID: "lm-cli", OidcTokenEndpoint: srv.URL}
	if _, _, err := bearerToken(srv.Client(), srv.URL, ctx, time.Now()); err == nil {
		t.Fatal("expired token with no refresh token must return an error")
	}
}

func TestApplyRefresh_missingContext_doesNotCreate(t *testing.T) {
	cfg := &config.Config{CurrentContext: "ghost", Contexts: map[string]config.Context{"real": {Server: "s"}}}
	if applyRefresh(cfg, "ghost", &refreshed{Access: "A"}) {
		t.Fatal("missing context must return false")
	}
	if _, exists := cfg.Contexts["ghost"]; exists {
		t.Fatal("must not create a ghost context entry")
	}
}

func TestApplyRefresh_present_updatesOnlyTokenFields(t *testing.T) {
	cfg := &config.Config{CurrentContext: "c", Contexts: map[string]config.Context{"c": {Server: "s", OidcClientID: "x"}}}
	if !applyRefresh(cfg, "c", &refreshed{Access: "A", Refresh: "R", ExpiresAt: 99}) {
		t.Fatal("present context must return true")
	}
	got := cfg.Contexts["c"]
	if got.Access != "A" || got.Refresh != "R" || got.ExpiresAt != 99 || got.Server != "s" || got.OidcClientID != "x" {
		t.Fatalf("update wrong or clobbered other fields: %+v", got)
	}
}

func TestDo_refreshesOnceThenReusesInMemoryToken(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // hermetic FS for persistRefresh
	var tokenCalls, apiCalls int
	mux := http.NewServeMux()
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		tokenCalls++
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"NEW","refresh_token":"RT2","expires_in":3600}`))
	})
	mux.HandleFunc("/api/platform/version", func(w http.ResponseWriter, r *http.Request) {
		apiCalls++
		_, _ = w.Write([]byte(`{}`))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Access: "OLD", Refresh: "RT",
		ExpiresAt:    time.Now().Add(-time.Minute).Unix(),
		OidcClientID: "lm-cli", OidcTokenEndpoint: srv.URL + "/token"})

	if err := c.GET("/api/platform/version", nil); err != nil {
		t.Fatalf("GET 1: %v", err)
	}
	if err := c.GET("/api/platform/version", nil); err != nil {
		t.Fatalf("GET 2: %v", err)
	}
	if tokenCalls != 1 {
		t.Fatalf("expected exactly 1 refresh (in-memory reuse on 2nd call), got %d", tokenCalls)
	}
	if apiCalls != 2 {
		t.Fatalf("expected 2 api calls, got %d", apiCalls)
	}
}

func TestPersistRefresh_writesToClientContextNotCurrent(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	path, _ := config.DefaultPath()
	cfg := &config.Config{CurrentContext: "other", Contexts: map[string]config.Context{
		"other": {Server: "o"},
		"mine":  {Server: "m", OidcClientID: "x"},
	}}
	if err := cfg.Save(path); err != nil {
		t.Fatal(err)
	}
	c := NewForContext("mine", config.Context{Server: "m"})
	c.persistRefresh(&refreshed{Access: "A", Refresh: "R", ExpiresAt: 99})

	got, _ := config.LoadFromFile(path)
	if got.Contexts["mine"].Access != "A" {
		t.Fatalf("must persist to the client's own context 'mine', got %+v", got.Contexts["mine"])
	}
	if got.Contexts["other"].Access != "" {
		t.Fatalf("must NOT overwrite current-context 'other', got %+v", got.Contexts["other"])
	}
}

func TestBearerToken_staticTokenPassthrough(t *testing.T) {
	ctx := config.Context{Token: "STATIC"}
	got, refreshed, err := bearerToken(http.DefaultClient, "", ctx, time.Now())
	if err != nil || got != "STATIC" || refreshed != nil {
		t.Fatalf("static token path: got=%q refreshed=%v err=%v", got, refreshed, err)
	}
}

func TestReloadAndRetry_diskFresher_succeeds(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.FormValue("refresh_token") == "disk-fresh" {
			_, _ = w.Write([]byte(`{"access_token":"A2","refresh_token":"R2","expires_in":300}`))
			return
		}
		w.WriteHeader(400)
		_, _ = w.Write([]byte(`{"error":"invalid_grant"}`))
	}))
	defer srv.Close()

	ours := config.Context{Access: "A0", Refresh: "ours-stale", ExpiresAt: 0,
		OidcTokenEndpoint: srv.URL, OidcClientID: "lm-cli"}
	disk := ours
	disk.Refresh = "disk-fresh"
	load := func() (config.Context, bool) { return disk, true }

	tok, r, ok := reloadAndRetry(srv.Client(), load, ours, time.Now())
	if !ok || tok != "A2" || r == nil || r.Refresh != "R2" {
		t.Fatalf("expected transparent success via disk token, got ok=%v tok=%q r=%v", ok, tok, r)
	}
}

func TestReloadAndRetry_diskIdentical_fails(t *testing.T) {
	ours := config.Context{Refresh: "same", OidcTokenEndpoint: "http://x", OidcClientID: "lm-cli"}
	load := func() (config.Context, bool) { return ours, true }
	if _, _, ok := reloadAndRetry(http.DefaultClient, load, ours, time.Now()); ok {
		t.Fatal("identical disk token must not retry — genuinely dead")
	}
}

func TestReloadAndRetry_noConfigOnDisk_fails(t *testing.T) {
	ours := config.Context{Refresh: "x", OidcTokenEndpoint: "http://x", OidcClientID: "lm-cli"}
	load := func() (config.Context, bool) { return config.Context{}, false }
	if _, _, ok := reloadAndRetry(http.DefaultClient, load, ours, time.Now()); ok {
		t.Fatal("no on-disk config must fail")
	}
}
