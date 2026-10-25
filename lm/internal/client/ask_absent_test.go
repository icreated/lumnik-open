package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// askHub answers every Ask door with status/body and /api/bff/v1/me with edition (#1581).
// meCalls counts the edition reads, so a test can say none happened.
func askHub(t *testing.T, status int, body, edition string, meCalls *int) *Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/bff/v1/me" {
			*meCalls++
			if edition == "" {
				w.WriteHeader(500)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]string{"edition": edition})
			return
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return New(config.Context{Server: srv.URL, Token: "tok"})
}

// The open edition's RESTEasy answer on an unrouted /api/platform/rag/* path.
const unrouted = `{"error":"Unable to find matching target resource method"}`

func TestAskDoorsOnAnOpenHubNameTheEdition(t *testing.T) {
	doors := map[string]func(c *Client) error{
		"ask":     func(c *Client) error { _, err := c.RagAsk("q", "", "", ""); return err },
		"chat":    func(c *Client) error { _, err := c.RagChat("q", "", 5); return err },
		"search":  func(c *Client) error { _, err := c.RagSearch("q", 5, ""); return err },
		"corpora": func(c *Client) error { _, err := c.RagCorpora(); return err },
		"scopes":  func(c *Client) error { _, err := c.RagScopes(); return err },
	}
	for name, call := range doors {
		t.Run(name, func(t *testing.T) {
			me := 0
			err := call(askHub(t, 404, unrouted, "open", &me))
			require.Error(t, err)
			assert.Contains(t, err.Error(), "this hub's build has no Ask")
			assert.Contains(t, err.Error(), "lumnik Pro")
			assert.NotContains(t, err.Error(), "resource method")
		})
	}
}

func TestAskDoorOnAProHubWithTheAIOffSaysSwitchedOff(t *testing.T) {
	me := 0
	_, err := askHub(t, 404, "", "commercial", &me).RagAsk("q", "", "", "")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "this hub has Ask switched off")
	assert.Contains(t, err.Error(), "LUMNIK_AI_ENABLED")
}

func TestAProHub404WithAReasonKeepsTheReason(t *testing.T) {
	me := 0
	_, err := askHub(t, 404, `{"error":"no user record on this hub for user_id 7"}`, "commercial", &me).RagScopes()
	require.Error(t, err)
	assert.Equal(t, "API error 404: no user record on this hub for user_id 7", err.Error())
}

func TestAnUnreadableEditionKeepsTheHubsAnswer(t *testing.T) {
	me := 0
	_, err := askHub(t, 404, unrouted, "", &me).RagAsk("q", "", "", "")
	require.Error(t, err)
	assert.Equal(t, "API error 404: Unable to find matching target resource method", err.Error())
}

func TestANon404AskFailureIsNotReinterpreted(t *testing.T) {
	me := 0
	_, err := askHub(t, 503, `{"error":"model unreachable"}`, "open", &me).RagAsk("q", "", "", "")
	require.Error(t, err)
	assert.True(t, strings.HasPrefix(err.Error(), "API error 503"), err.Error())
	assert.Equal(t, 0, me, "only a 404 asks the hub its edition")
}
