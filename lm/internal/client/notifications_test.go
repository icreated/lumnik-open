package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNotificationRules_createListDelete(t *testing.T) {
	var paths []string
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		switch r.Method {
		case "POST":
			_ = json.NewDecoder(r.Body).Decode(&body)
			w.WriteHeader(201)
			_, _ = w.Write([]byte(`{"id":3,"eventPattern":"decision.*","recipient":"adv@client.example","template":"event-notification","enabled":true}`))
		case "GET":
			_, _ = w.Write([]byte(`[{"id":3,"eventPattern":"decision.*","recipient":"adv@client.example","template":"event-notification","enabled":true}]`))
		case "DELETE":
			w.WriteHeader(204)
		}
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL, Token: "static"})
	created, err := c.NotificationRuleCreate(NotificationRuleCreate{
		EventPattern: "decision.*", Recipient: "adv@client.example"})
	require.NoError(t, err)
	assert.Equal(t, int64(3), created.ID)
	assert.Equal(t, "decision.*", body["eventPattern"])

	list, err := c.NotificationRuleList()
	require.NoError(t, err)
	require.Len(t, list, 1)
	assert.Equal(t, "adv@client.example", list[0].Recipient)

	require.NoError(t, c.NotificationRuleDelete(3))
	assert.Contains(t, paths, "POST /api/notifications")
	assert.Contains(t, paths, "GET /api/notifications")
	assert.Contains(t, paths, "DELETE /api/notifications/3")
}
