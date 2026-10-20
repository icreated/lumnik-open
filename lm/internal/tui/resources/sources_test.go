package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/types"
)

// The describe panel's "at:" line (last run) renders local, second precision — not the raw
// RFC3339 the hub sends. Expected value computed, not hardcoded, so this does not depend on
// the machine running it being on any particular timezone.
func TestSourcesDescribe_lastRunAtIsLocal(t *testing.T) {
	lastRun, err := time.Parse(time.RFC3339, "2026-08-15T09:12:04Z")
	if err != nil {
		t.Fatalf("the sample timestamp must parse: %v", err)
	}

	s := NewSources(nil, client.New(config.Context{Server: "http://127.0.0.1:0"}), nil, nil)
	s.items = []types.Connector{{ID: "c1", TenantID: 1, Name: "ad_user", TypeID: "csv-file",
		Enabled: true, Status: "Completed", LastRunAt: &lastRun}}
	s.table.Select(1, 0)
	_, body, ok := s.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	want := "at:        " + lastRun.In(time.Local).Format("2006-01-02 15:04:05")
	if !strings.Contains(body, want) {
		t.Errorf("describe body missing %q:\n%s", want, body)
	}
	if strings.Contains(body, "2026-08-15T09:12:04Z") {
		t.Errorf("describe body should render local-formatted, not the raw ISO string:\n%s", body)
	}
}

// With the panel open, every arrow key re-describes the new row ON the event loop. The list and
// the get serve the same ConnectorAdminDto.fromWithRun shape, so the row already holds the whole
// detail: a GET per key re-fetched it and froze the keys for a round trip each time (#891).
func TestSourcesDescribe_rendersTheListedRowWithoutAGet(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		_, _ = w.Write([]byte(`{"id":"c1","tenantId":1,"name":"from-the-get","typeId":"csv-file","enabled":true}`))
	}))
	defer srv.Close()

	s := NewSources(nil, client.New(config.Context{Server: srv.URL}), nil, nil)
	s.items = []types.Connector{{ID: "c1", TenantID: 7, Name: "ad_user", TypeID: "jdbc-generic",
		Schedule: "0 * * * *", Window: "01:00-05:00", Enabled: true, Status: "Running",
		Config: map[string]any{"url": "jdbc:postgresql://legacy/erp"}}}
	s.table.Select(1, 0)

	title, body, ok := s.SelectedDescribe()
	if !ok {
		t.Fatal("describe !ok")
	}
	if n := hits.Load(); n != 0 {
		t.Errorf("describe called the hub %d time(s); it must render the listed row", n)
	}
	if title != "ad_user" {
		t.Errorf("title = %q, want the listed row's name", title)
	}
	for _, want := range []string{"tenant:    7", "type_id:   jdbc-generic", "schedule:  0 * * * *",
		"window:    01:00-05:00", "Running", "jdbc:postgresql://legacy/erp"} {
		if !strings.Contains(body, want) {
			t.Errorf("describe body missing %q:\n%s", want, body)
		}
	}
}

// After t changes a connector's scopes, the page offers to re-stamp its indexed RAG chunks — the
// `lm connector scope sync` the CLI has (#1138). Only a yes reaches the retag door; the offer names
// the connector, and the answer says how many chunks it re-stamped.
func TestSourcesOfferScopeSync_retagsOnlyOnAYes(t *testing.T) {
	for _, answer := range []bool{false, true} {
		var retags atomic.Int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodPost && r.URL.Path == "/api/platform/connectors/id-erp-orders/retag" {
				retags.Add(1)
				_, _ = w.Write([]byte(`{"chunksUpdated":42}`))
				return
			}
			http.NotFound(w, r)
		}))
		var toasts, questions []string
		s := NewSources(nil, client.New(config.Context{Server: srv.URL}), nil, func(m string) { toasts = append(toasts, m) })
		s.SetConfirm(func(q, label string, yes func()) {
			questions = append(questions, q+" ["+label+"]")
			if answer {
				yes()
			}
		})
		s.renderList(conns("erp-orders"), nil)
		s.ScopesSet("id-erp-orders", []string{"ventes"}, []string{"ventes"}) // unchanged: nothing to re-stamp
		s.ScopesSet("id-erp-orders", []string{"ventes"}, []string{"ventes", "compta"})
		srv.Close()

		if len(questions) != 1 || !strings.Contains(questions[0], `"erp-orders"`) || !strings.Contains(questions[0], "[Re-stamp]") {
			t.Fatalf("questions = %q, want one naming the connector, answered by Re-stamp", questions)
		}
		want := int32(0)
		if answer {
			want = 1
		}
		if got := retags.Load(); got != want {
			t.Errorf("answer %v: %d retag(s) reached the hub, want %d", answer, got, want)
		}
		if answer && (len(toasts) != 1 || !strings.Contains(toasts[0], "42")) {
			t.Errorf("toasts = %q, want the re-stamped chunk count", toasts)
		}
	}
}

// Del asks the hub first (?dryRun=true), as lm connector delete does (#1218): the entity that blocks
// the delete is named before any question, and the question names each table and the rows that go.
func TestSourcesDelete_asksTheHubFirst(t *testing.T) {
	for _, tc := range []struct {
		name         string
		front        bool
		status       int
		body         string
		wantQuestion string // "" = no question at all
		wantErr      string
	}{
		{"refused", true, http.StatusConflict,
			`{"error":"table connector.t_orders feeds entity order — remove the entity first (lm entity delete order)"}`,
			"", "feeds entity order"},
		{"rows", true, http.StatusOK, `{"dryRun":true,"tables":[{"table":"connector.t_orders","rows":1204}]}`,
			`Delete connector "erp-orders"? Goes with it: 1204 rows of connector.t_orders, its run history and RAG chunks.`, ""},
		{"credential", true, http.StatusOK, `{"dryRun":true,"tables":[],"credential":"erp--credential"}`,
			`Delete connector "erp-orders"? Goes with it: credential erp--credential, its run history and RAG chunks.`, ""},
		{"no table", true, http.StatusOK, `{"dryRun":true,"tables":[]}`,
			`Delete connector "erp-orders"? Goes with it: its run history and RAG chunks.`, ""},
		{"left before the answer", false, http.StatusOK, `{"dryRun":true,"tables":[]}`, "", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls []string
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodDelete {
					http.NotFound(w, r) // the refresh after the delete
					return
				}
				calls = append(calls, r.URL.RequestURI())
				if r.URL.Query().Get("dryRun") == "true" {
					w.WriteHeader(tc.status)
					_, _ = w.Write([]byte(tc.body))
					return
				}
				w.WriteHeader(http.StatusNoContent)
			}))
			defer srv.Close()
			var errs, questions []string
			s := NewSources(nil, client.New(config.Context{Server: srv.URL}), func(m string) { errs = append(errs, m) }, nil)
			s.SetFront(func() bool { return tc.front })
			s.SetConfirm(func(q, label string, yes func()) { questions = append(questions, q+" ["+label+"]"); yes() })
			s.renderList(conns("erp-orders"), nil)
			s.table.Select(1, 0)

			s.delete()

			const dry, wet = "/api/platform/connectors/id-erp-orders?dryRun=true", "/api/platform/connectors/id-erp-orders"
			wantCalls := []string{dry}
			if tc.wantQuestion != "" {
				wantCalls = append(wantCalls, wet)
				if len(questions) != 1 || questions[0] != tc.wantQuestion+" [Delete]" {
					t.Errorf("questions = %q, want [%q]", questions, tc.wantQuestion+" [Delete]")
				}
			} else if len(questions) != 0 {
				t.Errorf("asked %q, want no question", questions)
			}
			if strings.Join(calls, "|") != strings.Join(wantCalls, "|") {
				t.Errorf("calls = %q, want %q", calls, wantCalls)
			}
			if tc.wantErr != "" && (len(errs) != 1 || !strings.Contains(errs[0], tc.wantErr)) {
				t.Errorf("errs = %q, want one naming %q", errs, tc.wantErr)
			}
		})
	}
}

// y on :sources read the whole connector on the event loop, as e did before #1156: a hub holding the
// GET held every key with it (#1158). The GET now runs off the loop under y's inFlight key, and the
// yank happens in its follow-up, once. A second y while the GET is out: "sources y" in the heldPress
// table.
func TestSources_yReadsTheConnectorOffTheLoopAndYanksOnce(t *testing.T) {
	arrived, release := make(chan struct{}, 8), make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/platform/connectors":
			_, _ = w.Write([]byte(`[{"id":"c1","name":"ad_user","typeId":"csv-file","enabled":true}]`))
		case "/api/platform/connectors/c1":
			arrived <- struct{}{}
			<-release // the hub holds the GET
			_, _ = w.Write([]byte(`{"id":"c1","name":"ad_user","typeId":"csv-file","enabled":true}`))
		default:
			_, _ = w.Write([]byte(`{}`))
		}
	}))
	t.Cleanup(srv.Close)

	app := runningSimApp(t)
	var once sync.Once
	free := func() { once.Do(func() { close(release) }) }
	t.Cleanup(free) // runs first: a failed test must not leave srv.Close waiting on a held request

	yanked := make(chan string, 4)
	var press func()
	onLoopWithin(app, func() {
		p := NewSources(nil, client.New(config.Context{Server: srv.URL}), nil, nil)
		p.Refresh()
		p.app = app
		p.table.Select(1, 0)
		press = func() { p.YankLater(func(text string) { yanked <- text }) }
	})

	go app.QueueUpdate(press) // no wait on it: before #1158 this press held the loop
	select {
	case <-arrived:
	case <-time.After(5 * time.Second):
		t.Fatal("y never asked the hub for the connector")
	}
	if !onLoopWithin(app, func() {}) {
		t.Fatal("the loop is held while the hub holds the GET: y reads the connector on the event loop")
	}
	if len(yanked) != 0 {
		t.Fatal("y yanked before the hub answered")
	}

	free()
	select {
	case text := <-yanked:
		if !strings.Contains(text, "name: ad_user") {
			t.Errorf("yanked %q, want the connector's YAML", text)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("y never yanked after the hub answered")
	}
	onLoopWithin(app, func() {}) // a second follow-up queued meanwhile lands before this
	if n := len(yanked); n != 0 {
		t.Fatalf("y yanked %d more time(s), want once", n)
	}
}

// e on :sources read the connector on the event loop: a hub holding the GET held every key with it
// (#1156). The GET now runs off the loop under e's inFlight key, and the editor opens in its
// follow-up, once. A second e while the GET is out: "sources e" in the heldPress table.
func TestSources_eReadsTheConnectorOffTheLoopAndOpensTheEditorOnce(t *testing.T) {
	var gets atomic.Int32
	arrived, release := make(chan struct{}, 8), make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/platform/connectors":
			_, _ = w.Write([]byte(`[{"id":"c1","name":"ad_user","typeId":"csv-file","enabled":true}]`))
		case "/api/platform/connectors/c1":
			gets.Add(1)
			arrived <- struct{}{}
			<-release // the hub holds the GET
			_, _ = w.Write([]byte(`{"id":"c1","name":"ad_user","typeId":"csv-file","enabled":true}`))
		default:
			_, _ = w.Write([]byte(`{}`))
		}
	}))
	t.Cleanup(srv.Close)

	app := runningSimApp(t)
	var once sync.Once
	free := func() { once.Do(func() { close(release) }) }
	t.Cleanup(free) // runs first: a failed test must not leave srv.Close waiting on a held request

	var opened atomic.Int32
	var press func()
	onLoopWithin(app, func() {
		p := NewSources(nil, client.New(config.Context{Server: srv.URL}), nil, nil)
		p.suspend = func(func()) { opened.Add(1) } // the editor: counted, never run
		p.Refresh()
		p.app = app
		p.table.Select(1, 0)
		press = pressOf(p.handleKey, tcell.KeyRune, 'e')
	})

	go app.QueueUpdate(press) // no wait on it: before #1156 this press held the loop
	select {
	case <-arrived:
	case <-time.After(5 * time.Second):
		t.Fatal("e never asked the hub for the connector")
	}
	if !onLoopWithin(app, func() {}) {
		t.Fatal("the loop is held while the hub holds the GET: e reads the connector on the event loop")
	}
	if n := opened.Load(); n != 0 {
		t.Fatalf("the editor opened %d time(s) before the hub answered", n)
	}

	free()
	deadline := time.Now().Add(5 * time.Second)
	for opened.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	onLoopWithin(app, func() {}) // a second follow-up queued meanwhile lands before this
	if n := opened.Load(); n != 1 {
		t.Fatalf("the editor opened %d time(s), want 1", n)
	}
	if n := gets.Load(); n != 1 {
		t.Fatalf("%d GETs reached the hub, want 1", n)
	}
}
