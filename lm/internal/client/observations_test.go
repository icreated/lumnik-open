package client

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestObservationList_decodesTapeAndNullAlert(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.RequestURI()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[
			{"workflow":"commande","docKey":"CMD-1","state":"draft",
			 "since":"2026-08-01T10:00:00Z","firstObservedAt":"2026-07-30T09:00:00Z","alertedAt":"2026-08-12T08:00:00Z"},
			{"workflow":"commande","docKey":"CMD-2","state":"sent",
			 "since":"2026-08-10T10:00:00Z","firstObservedAt":"2026-08-10T10:00:00Z","alertedAt":null}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "static"})
	got, err := c.ObservationList(200, "")
	require.NoError(t, err)
	assert.Equal(t, "/api/platform/workflows/observations?limit=200", gotPath)
	require.Len(t, got, 2)
	assert.NotNil(t, got[0].AlertedAt, "an alerted row must carry its alertedAt")
	assert.Nil(t, got[1].AlertedAt, "a non-alerted row must decode alertedAt as nil, not a zero time")
	assert.Equal(t, 2026, got[0].Since.Year())
	assert.Equal(t, "CMD-1", got[0].DocKey)
	assert.Equal(t, "draft", got[0].State)
}

// #1361: one workflow's documents, the name escaped.
func TestObservationList_sendsTheWorkflow(t *testing.T) {
	var got url.Values
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Query()
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	_, err := New(config.Context{Server: srv.URL, Token: "static"}).ObservationList(50, "scenario-freezer-door-c")
	require.NoError(t, err)
	assert.Equal(t, "scenario-freezer-door-c", got.Get("workflow"))
	assert.Equal(t, "50", got.Get("limit"))
}

func TestObservationStats_decodesAndNullMedian(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.RequestURI()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[
			{"workflow":"wf-a","watched":3,"dormant":2,"medianDetectionSeconds":172800},
			{"workflow":"wf-b","watched":1,"dormant":0,"medianDetectionSeconds":null}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "static"})
	got, err := c.ObservationStats()
	require.NoError(t, err)
	assert.Equal(t, "/api/platform/workflows/observations/stats", gotPath)
	require.Len(t, got, 2)
	assert.Equal(t, int64(3), got[0].Watched)
	assert.Equal(t, int64(2), got[0].Dormant)
	require.NotNil(t, got[0].MedianDetectionSeconds)
	assert.Equal(t, int64(172800), *got[0].MedianDetectionSeconds)
	assert.Nil(t, got[1].MedianDetectionSeconds, "no dormants must decode as nil, never zero")
}
