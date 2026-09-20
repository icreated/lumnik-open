package login

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
)

func freshLoginContext(server string) config.Context {
	return config.Context{Server: server, Access: "tok", ExpiresAt: time.Now().Add(time.Hour).Unix()}
}

func TestGreeting_OpenEdition_IsNamed(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{"edition": "open"})
	}))
	defer srv.Close()

	got := greeting("c", freshLoginContext(srv.URL))
	assert.Equal(t, "Logged in to lumnik Open Edition.", got)
}

func TestGreeting_CommercialEdition_IsPlain(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]string{"edition": "commercial"})
	}))
	defer srv.Close()

	got := greeting("c", freshLoginContext(srv.URL))
	assert.Equal(t, "Logged in.", got)
}

func TestGreeting_EnrichmentFailure_IsPlain(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
	}))
	defer srv.Close()

	got := greeting("c", freshLoginContext(srv.URL))
	assert.Equal(t, "Logged in.", got)
}
