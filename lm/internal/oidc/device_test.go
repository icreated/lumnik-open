package oidc

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestRequestDeviceCode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.FormValue("client_id") != "lm-cli" {
			t.Errorf("client_id = %q", r.FormValue("client_id"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"device_code":"DEV","user_code":"WXYZ","verification_uri":"https://kc/dev","verification_uri_complete":"https://kc/dev?user_code=WXYZ","expires_in":600,"interval":1}`))
	}))
	defer srv.Close()

	d, err := RequestDeviceCode(srv.Client(), srv.URL, "lm-cli")
	if err != nil {
		t.Fatalf("RequestDeviceCode: %v", err)
	}
	if d.DeviceCode != "DEV" || d.UserCode != "WXYZ" || d.Interval != 1 {
		t.Fatalf("unexpected device resp: %+v", d)
	}
}

func TestPollToken_pendingThenSuccess(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Header().Set("Content-Type", "application/json")
		if calls < 2 {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"authorization_pending"}`))
			return
		}
		_, _ = w.Write([]byte(`{"access_token":"AT","refresh_token":"RT","expires_in":300}`))
	}))
	defer srv.Close()

	tok, err := PollToken(context.Background(), srv.Client(), srv.URL, "lm-cli", "DEV", 1*time.Millisecond)
	if err != nil {
		t.Fatalf("PollToken: %v", err)
	}
	if tok.AccessToken != "AT" || tok.RefreshToken != "RT" || tok.ExpiresIn != 300 {
		t.Fatalf("unexpected token: %+v", tok)
	}
	if calls < 2 {
		t.Fatalf("expected at least 2 polls, got %d", calls)
	}
}

func TestRefresh(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if r.FormValue("grant_type") != "refresh_token" || r.FormValue("refresh_token") != "RT" {
			t.Errorf("bad refresh form: %v", r.Form)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"AT2","refresh_token":"RT2","expires_in":300}`))
	}))
	defer srv.Close()

	tok, err := Refresh(srv.Client(), srv.URL, "lm-cli", "RT")
	if err != nil {
		t.Fatalf("Refresh: %v", err)
	}
	if tok.AccessToken != "AT2" || tok.RefreshToken != "RT2" {
		t.Fatalf("unexpected refreshed token: %+v", tok)
	}
}

func TestPollToken_hardError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"access_denied"}`))
	}))
	defer srv.Close()
	if _, err := PollToken(context.Background(), srv.Client(), srv.URL, "lm-cli", "DEV", 1*time.Millisecond); err == nil || !strings.Contains(err.Error(), "access_denied") {
		t.Fatalf("want access_denied error, got %v", err)
	}
}

func TestPollToken_cancelAbortsInFlightRequest(t *testing.T) {
	block := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-block // hang until the test ends
	}))
	defer srv.Close()
	defer close(block)

	hc := &http.Client{Timeout: 3 * time.Second} // bounds the pre-fix (non-ctx) case so red terminates
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(20 * time.Millisecond); cancel() }()

	start := time.Now()
	_, err := PollToken(ctx, hc, srv.URL, "lm-cli", "DEV", time.Hour)
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("want a cancellation error")
	}
	if elapsed > time.Second {
		t.Fatalf("ctx cancel did not abort the in-flight request (took %v); request must use the context", elapsed)
	}
}

func TestPollToken_timesOutOnContextDeadline(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":"authorization_pending"}`))
	}))
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, err := PollToken(ctx, srv.Client(), srv.URL, "lm-cli", "DEV", 5*time.Millisecond)
	if err == nil || !strings.Contains(err.Error(), "timed out") {
		t.Fatalf("want a timeout error, got %v", err)
	}
}
