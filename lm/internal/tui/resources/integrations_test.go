package resources

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

// A webhook URL is often the credential itself (a chat tool's incoming hook carries its token in
// the path), so this one stands for every target: nothing past its host may reach the screen.
const hookURL = "https://bot:pa55@hooks.example.com/services/T0K3N?key=k3y"

func integrationsHub(t *testing.T, calls *[]string, bodies map[string]map[string]any) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*calls = append(*calls, r.Method+" "+r.URL.RequestURI())
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		bodies[r.Method+" "+r.URL.Path] = body
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/platform/integrations":
			_, _ = w.Write([]byte(`[{"id":7,"name":"ops[red]","targetUrl":"` + hookURL + `","platform":"generic",` +
				`"authMode":"HMAC","eventTypes":["decision.*"],"enabled":true,"hasSecret":true}]`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/platform/integrations/7/deliveries":
			_, _ = w.Write([]byte(`[` +
				`{"id":2,"eventType":"decision.dormant.po","httpStatus":502,"error":"bad gateway","durationMs":40,"attemptedAt":"2026-10-16T10:00:00Z"},` +
				`{"id":1,"eventType":"org.created","httpStatus":200,"durationMs":12,"attemptedAt":"2026-10-16T09:00:00Z"}]`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/platform/integrations":
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"id":8,"name":"crm","eventTypes":["decision.*"],"enabled":true}`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/platform/integrations/7/test":
			_, _ = w.Write([]byte(`{"ok":false,"httpStatus":null,"error":"ConnectException: ` + hookURL + ` refused","durationMs":5}`))
		case r.Method == http.MethodDelete:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

// :integrations lists each target with its recent delivery health, and shows of its URL only the
// scheme and host — not the user, the path or the query, any of which can carry the credential.
func TestIntegrations_listHealthNeverTheURLCredential(t *testing.T) {
	var calls []string
	srv := integrationsHub(t, &calls, map[string]map[string]any{})
	p := NewIntegrations(nil, client.New(config.Context{Server: srv.URL}), nil, nil)
	p.Refresh()
	p.table.Select(1, 0)

	var row []string
	for c := 0; c < p.table.GetColumnCount(); c++ {
		row = append(row, p.table.GetCell(1, c).Text)
	}
	joined := strings.Join(row, "|")
	for _, want := range []string{"ops[red[]", "https://hooks.example.com", "decision.*", "1/2 ok", "last ✗ 502"} {
		if !strings.Contains(joined, want) {
			t.Errorf("row %q, missing %q", joined, want)
		}
	}
	_, body, ok := p.SelectedDescribe()
	if !ok {
		t.Fatal("no describe for the selected target")
	}
	for _, want := range []string{"signing secret: set", "decision.dormant.po", "bad gateway"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe %q, missing %q", body, want)
		}
	}
	for _, leak := range []string{"T0K3N", "k3y", "pa55"} {
		if strings.Contains(joined+body, leak) {
			t.Errorf("a credential part of the URL reached the screen (%q): %q", leak, joined+body)
		}
	}
	if !strings.Contains(strings.Join(calls, "\n"), "GET /api/platform/integrations/7/deliveries?limit=10") {
		t.Errorf("health was not read from the target's deliveries: %v", calls)
	}
}

// n registers a target through a guided prompt — the URL and the signing secret typed masked —,
// T fires a test event and says how it went, Del removes the target after naming it.
func TestIntegrations_addTestRemove(t *testing.T) {
	var calls []string
	bodies := map[string]map[string]any{}
	srv := integrationsHub(t, &calls, bodies)
	var said []string
	sink := func(m string) { said = append(said, m) }
	p := NewIntegrations(nil, client.New(config.Context{Server: srv.URL}), sink, sink)
	answers := []string{"crm", "https://crm.example.com/hook/S3CR3TPATH", "", "s3cr3t", ""}
	var masked []bool
	p.SetAsk(func(label string, mask bool, done func(string)) {
		masked = append(masked, mask)
		a := answers[0]
		answers = answers[1:]
		done(a)
	})
	p.SetConfirm(func(q, label string, yes func()) { said = append(said, q+" ["+label+"]"); yes() })
	p.Refresh()
	p.table.Select(1, 0)
	key := func(k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }

	key(tcell.KeyRune, 'n') // name, URL, events, secret
	key(tcell.KeyRune, 'T') // event type: Enter for the default
	key(tcell.KeyDelete, 0)

	created := bodies["POST /api/platform/integrations"]
	if created["name"] != "crm" || created["targetUrl"] != "https://crm.example.com/hook/S3CR3TPATH" || created["secret"] != "s3cr3t" {
		t.Errorf("create body = %v", created)
	}
	if ev, _ := created["eventTypes"].([]any); len(ev) != 1 || ev[0] != "decision.*" {
		t.Errorf("events = %v, want the CLI's default decision.*", created["eventTypes"])
	}
	if got := bodies["POST /api/platform/integrations/7/test"]["eventType"]; got != "org.created" {
		t.Errorf("test fired %v, want org.created", got)
	}
	if fmt.Sprint(masked) != "[false true false true false]" {
		t.Errorf("masked = %v, want the URL and the secret masked", masked)
	}
	if !strings.Contains(strings.Join(calls, "\n"), "DELETE /api/platform/integrations/7") {
		t.Errorf("no delete in %v", calls)
	}
	everything := strings.Join(said, "|")
	for _, want := range []string{"registered: crm", "test org.created → ops[red]: delivery failed", "https://hooks.example.com", `"ops[red]"`, "[Remove]"} {
		if !strings.Contains(everything, want) {
			t.Errorf("said %q, missing %q", everything, want)
		}
	}
	for _, leak := range []string{"s3cr3t", "S3CR3TPATH", "T0K3N", "k3y", "pa55"} {
		if strings.Contains(everything, leak) {
			t.Errorf("%q reached the screen: %q", leak, everything)
		}
	}
}

// / narrows the targets to the rows holding a text, over the columns shown — never the hidden URL.
func TestIntegrations_filterSearchesShownColumnsOnly(t *testing.T) {
	var calls []string
	srv := integrationsHub(t, &calls, map[string]map[string]any{})
	p := NewIntegrations(nil, client.New(config.Context{Server: srv.URL}), nil, nil)
	p.Refresh()
	p.SetTextFilter("T0K3N")
	if len(p.items) != 0 {
		t.Errorf("the filter matched on the hidden URL path: %v", p.items)
	}
	p.SetTextFilter("ops")
	if len(p.items) != 1 {
		t.Errorf("filter ops kept %d rows, want 1", len(p.items))
	}
}
