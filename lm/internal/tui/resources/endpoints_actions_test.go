package resources

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

// :endpoints controls each endpoint as lm endpoint does: ^P toggles it, C resets its cursor and
// Del purges its rows — the last two only after a yes (#1112).
func TestEndpoints_controls(t *testing.T) {
	var mu sync.Mutex
	var calls []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		mu.Lock()
		calls = append(calls, r.Method+" "+r.URL.RequestURI()+" "+strings.TrimSpace(string(body)))
		mu.Unlock()
		if strings.HasSuffix(r.URL.Path, "/purge") {
			_, _ = w.Write([]byte(`{"deleted":7}`))
			return
		}
		_, _ = w.Write([]byte(`[{"id":"e1","name":"orders","enabled":false}]`)) // the list after the toggle
	}))
	defer srv.Close()

	var toasts, questions []string
	answer := false
	p := NewEndpoints(nil, client.New(config.Context{Server: srv.URL}), func(m string) { toasts = append(toasts, m) }, nil)
	p.SetConfirm(func(q, label string, yes func()) {
		questions = append(questions, q+" ["+label+"]")
		if answer {
			yes()
		}
	})
	p.SetFilter("c1", "")
	p.renderList("c1", []types.Endpoint{{ID: "e1", Name: "orders", Enabled: true}}, nil)
	p.table.Select(1, 0)
	key := func(k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }

	key(tcell.KeyCtrlP, 0)
	key(tcell.KeyRune, 'C') // declined
	key(tcell.KeyDelete, 0) // declined
	answer = true
	key(tcell.KeyRune, 'C')
	key(tcell.KeyDelete, 0)

	joined := strings.Join(calls, "\n")
	for _, want := range []string{
		`PUT /api/platform/connectors/c1/endpoints/e1 {"enabled":false}`,
		"POST /api/platform/connectors/c1/endpoints/e1/reset-cursor",
		"POST /api/platform/connectors/c1/endpoints/e1/purge {}", // the dry runs carry ?dryRun=true (#1183)
	} {
		if strings.Count(joined, want) != 1 {
			t.Errorf("want exactly one %q, calls:\n%s", want, joined)
		}
	}
	if len(questions) != 4 || !strings.Contains(questions[0], `"orders"`) {
		t.Errorf("questions = %q", questions)
	}
	// neither deletes the endpoint: each yes names its act, not Delete (#1150)
	for i, label := range []string{"[Reset]", "[Purge]", "[Reset]", "[Purge]"} {
		if i < len(questions) && !strings.HasSuffix(questions[i], label) {
			t.Errorf("question %d = %q, want it answered by %s", i, questions[i], label)
		}
	}
	if !strings.Contains(strings.Join(toasts, "|"), "7 rows") {
		t.Errorf("purge did not say how many rows went: %q", toasts)
	}
}

// Del asks the hub first (?dryRun=true), as lm endpoint purge does: a refusal or an empty endpoint
// never reaches a question, and the question says how many rows will go (#1183).
func TestEndpoints_purgeAsksTheHubFirst(t *testing.T) {
	for _, tc := range []struct {
		name               string
		front              bool
		status             int
		body               string
		wantQuestion       string // "" = no question at all
		wantToast, wantErr string
	}{
		{"refused", true, http.StatusBadRequest, `{"error":"This endpoint writes to a connector table whose rows are not tracked per endpoint, so it cannot be purged on its own. Deleting the connector removes its data, along with its run history."}`,
			"", "", "Deleting the connector removes its data"}, // EndpointResource.NOT_PURGEABLE_ON_ITS_OWN
		{"no rows", true, http.StatusOK, `{"deleted":0,"dryRun":true}`, "", "nothing to purge", ""},
		{"one row", true, http.StatusOK, `{"deleted":1,"dryRun":true}`, `Purge 1 row endpoint "orders" has landed?`, "purged: 1 row of orders", ""},
		{"rows", true, http.StatusOK, `{"deleted":1204,"dryRun":true}`, `Purge 1204 rows endpoint "orders" has landed?`, "purged: 1204 rows of orders", ""},
		{"left before the answer", false, http.StatusOK, `{"deleted":1204,"dryRun":true}`, "", "", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls []string
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls = append(calls, r.URL.RequestURI())
				w.WriteHeader(tc.status) // the real purge, when asked, answers as its dry run did
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			var toasts, errs, questions []string
			p := NewEndpoints(nil, client.New(config.Context{Server: srv.URL}),
				func(m string) { toasts = append(toasts, m) }, func(m string) { errs = append(errs, m) })
			p.SetFront(func() bool { return tc.front })
			p.SetConfirm(func(q, _ string, yes func()) { questions = append(questions, q); yes() })
			p.SetFilter("c1", "")
			p.renderList("c1", []types.Endpoint{{ID: "e1", Name: "orders"}}, nil)
			p.table.Select(1, 0)

			p.handleKey(tcell.NewEventKey(tcell.KeyDelete, 0, tcell.ModNone))

			const dry, wet = "/api/platform/connectors/c1/endpoints/e1/purge?dryRun=true", "/api/platform/connectors/c1/endpoints/e1/purge"
			wantCalls := []string{dry}
			if tc.wantQuestion != "" {
				wantCalls = append(wantCalls, wet)
			}
			if strings.Join(calls, "|") != strings.Join(wantCalls, "|") {
				t.Errorf("calls = %q, want %q", calls, wantCalls)
			}
			switch {
			case tc.wantQuestion == "" && len(questions) != 0:
				t.Errorf("asked %q, want no question", questions)
			case tc.wantQuestion != "" && (len(questions) != 1 || questions[0] != tc.wantQuestion):
				t.Errorf("questions = %q, want %q", questions, tc.wantQuestion)
			}
			if tc.wantToast != "" && !strings.Contains(strings.Join(toasts, "|"), tc.wantToast) {
				t.Errorf("toasts = %q, want one holding %q", toasts, tc.wantToast)
			}
			if tc.wantErr == "" && len(errs) != 0 || !strings.Contains(strings.Join(errs, "|"), tc.wantErr) {
				t.Errorf("errors = %q, want %q", errs, tc.wantErr)
			}
		})
	}
}

// T pre-flights the selected endpoint (the request a run would send, nothing written) and P samples
// a path of the connector to draft an endpoint fragment — both shown in full (#1117).
func TestEndpoints_testAndProbe(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.RequestURI())
		switch {
		case strings.HasSuffix(r.URL.Path, "/test"):
			_, _ = w.Write([]byte(`{"endpoint":"orders","method":"GET","url":"https://api/x","status":200,"ok":true,"items":25,"sample":"{\"id\":1}"}`))
		default:
			_, _ = w.Write([]byte("path: /v1/customers\nitemsPath: data\n"))
		}
	}))
	defer srv.Close()
	var shown []string
	p := NewEndpoints(nil, client.New(config.Context{Server: srv.URL}), nil, nil)
	p.SetShow(func(title, body string) { shown = append(shown, title+"\n"+body) })
	p.SetAsk(func(_ string, _ bool, done func(string)) { done("/v1/customers") })
	p.SetFilter("c1", "")
	p.renderList("c1", []types.Endpoint{{ID: "e1", Name: "orders"}}, nil)
	p.table.Select(1, 0)
	p.handleKey(tcell.NewEventKey(tcell.KeyRune, 'T', tcell.ModNone))
	p.handleKey(tcell.NewEventKey(tcell.KeyRune, 'P', tcell.ModNone))
	if len(shown) != 2 || !strings.Contains(shown[0], "200") || !strings.Contains(shown[0], "25") ||
		!strings.Contains(shown[1], "itemsPath") {
		t.Errorf("shown = %q (calls %v)", shown, paths)
	}
}
