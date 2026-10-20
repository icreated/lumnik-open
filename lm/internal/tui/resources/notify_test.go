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

// :notify lists the email rules, adds one through a guided prompt and removes one after naming it.
func TestNotify_listAddRemove(t *testing.T) {
	var calls []string
	var created map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls = append(calls, r.Method+" "+r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		switch r.Method {
		case http.MethodGet:
			_, _ = w.Write([]byte(`[{"id":3,"eventPattern":"decision.*","entityFilter":"connector.t_po",` +
				`"recipient":"ops@example.com","template":"event-[b]","enabled":true}]`))
		case http.MethodPost:
			_ = json.NewDecoder(r.Body).Decode(&created)
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"id":4,"eventPattern":"run.failed","recipient":"it@example.com","enabled":true}`))
		case http.MethodDelete:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	defer srv.Close()

	var said []string
	sink := func(m string) { said = append(said, m) }
	p := NewNotify(nil, client.New(config.Context{Server: srv.URL}), sink, sink)
	answers := []string{"run.failed", "it@example.com", "", "decision-alert"}
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

	var row []string
	for c := 0; c < p.table.GetColumnCount(); c++ {
		row = append(row, p.table.GetCell(1, c).Text)
	}
	if got := strings.Join(row, "|"); !strings.Contains(got, "decision.*") || !strings.Contains(got, "ops@example.com") ||
		!strings.Contains(got, "connector.t_po") || !strings.Contains(got, "event-[b[]") {
		t.Errorf("row = %q", got)
	}
	if _, body, ok := p.SelectedDescribe(); !ok || !strings.Contains(body, "ops@example.com") {
		t.Errorf("describe = %q", body)
	}

	key := func(k tcell.Key, r rune) { p.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }
	key(tcell.KeyRune, 'n') // events, recipient, entity filter, template
	key(tcell.KeyDelete, 0)

	if created["eventPattern"] != "run.failed" || created["recipient"] != "it@example.com" || created["template"] != "decision-alert" {
		t.Errorf("create body = %v", created)
	}
	if _, sent := created["entityFilter"]; sent {
		t.Errorf("an empty entity filter was sent: %v", created)
	}
	if fmt.Sprint(masked) != "[false false false false]" {
		t.Errorf("masked = %v — a rule carries no secret", masked)
	}
	if !strings.Contains(strings.Join(calls, "\n"), "DELETE /api/notifications/3") {
		t.Errorf("no delete in %v", calls)
	}
	everything := strings.Join(said, "|")
	for _, want := range []string{"rule 4: run.failed → it@example.com", "decision.* → ops@example.com", "[Remove]"} {
		if !strings.Contains(everything, want) {
			t.Errorf("said %q, missing %q", everything, want)
		}
	}
}

// ENABLED reads yes/no on every page that has it, as on :sources, :endpoints and the manifest pages —
// :notify and :integrations said true/false (#1175).
func TestEnabledColumn_isYesNoEverywhere(t *testing.T) {
	on, off := ruleColumns(client.NotificationRule{Enabled: true}), (&Integrations{}).columns(client.Integration{})
	if got := on[len(on)-1]; got != "yes" {
		t.Errorf(":notify ENABLED = %q", got)
	}
	if got := off[4]; got != "no" {
		t.Errorf(":integrations ENABLED = %q", got)
	}
}
