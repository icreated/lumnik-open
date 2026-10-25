package client

import (
	"net/http"
	"net/url"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestInboxList_parses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/inbox", r.URL.Path)
		assert.Equal(t, "25", r.URL.Query().Get("limit"))
		assert.Equal(t, "true", r.URL.Query().Get("all"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"id":4212,"occurredAt":"2026-07-05T10:00:00Z","type":"decision.lyon-client-arrive",
			"process":"lyon-client-arrive","message":"Nouveau client à Lyon",
			"entity":"connector.t_cust_csv","entityKey":"e717","acked":false}]`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	entries, err := c.InboxList(InboxQuery{Limit: 25, All: true})
	require.NoError(t, err)
	require.Len(t, entries, 1)
	assert.Equal(t, InboxEntry{
		ID: 4212, OccurredAt: "2026-07-05T10:00:00Z", Type: "decision.lyon-client-arrive",
		Process: "lyon-client-arrive", Message: "Nouveau client à Lyon",
		Entity: "connector.t_cust_csv", EntityKey: "e717", Acked: false,
	}, entries[0])
}

// #1356: the filters travel as query parameters, escaped; an empty one is not sent.
func TestInboxList_sendsTheFilters(t *testing.T) {
	var got url.Values
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Query()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})

	_, err := c.InboxList(InboxQuery{Limit: 50, Process: "workflow:orders", Type: "decision.dormant.orders"})
	require.NoError(t, err)
	assert.Equal(t, "workflow:orders", got.Get("process"))
	assert.Equal(t, "decision.dormant.orders", got.Get("type"))
	assert.False(t, got.Has("all"))

	_, err = c.InboxList(InboxQuery{Limit: 50})
	require.NoError(t, err)
	assert.False(t, got.Has("process"))
	assert.False(t, got.Has("type"))
}

func TestInboxAck_postsTheAckPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "POST", r.Method)
		assert.Equal(t, "/api/inbox/4212/ack", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"acked":true}`))
	}))
	defer srv.Close()
	c := New(config.Context{Server: srv.URL})
	require.NoError(t, c.InboxAck(4212))
}
