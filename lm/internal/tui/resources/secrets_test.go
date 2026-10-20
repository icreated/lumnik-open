package resources

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/icreated/lumnik-open/lm/internal/output"
)

// :secrets stores and rotates credentials without leaving the TUI, asks the value masked, and
// never lets it reach a toast, the describe panel or the table (#1113).
func TestSecrets_setRotateDelete(t *testing.T) {
	var calls []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		calls = append(calls, r.Method+" "+r.URL.Path+" value="+body["value"])
		if r.Method == http.MethodGet {
			_, _ = w.Write([]byte(`[{"id":"1","name":"erp-db","description":"ERP read user"}]`))
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	var masked []bool
	answers := []string{"crm-api", "s3cr3t-new", "s3cr3t-rot"}
	var said []string
	s := NewSecrets(nil, client.New(config.Context{Server: srv.URL}),
		func(m string) { said = append(said, m) }, func(m string) { said = append(said, m) })
	s.SetAsk(func(label string, mask bool, done func(string)) {
		masked = append(masked, mask)
		a := answers[0]
		answers = answers[1:] // before done: the value prompt is asked from inside the name's
		done(a)
	})
	s.SetConfirm(func(q, label string, yes func()) { said = append(said, q+" ["+label+"]"); yes() })
	s.Refresh()
	s.table.Select(1, 0)
	key := func(k tcell.Key, r rune) { s.handleKey(tcell.NewEventKey(k, r, tcell.ModNone)) }

	key(tcell.KeyRune, 'n') // name, then value
	key(tcell.KeyRune, 'e') // rotate erp-db
	key(tcell.KeyDelete, 0)

	joined := strings.Join(calls, "\n")
	for _, want := range []string{
		"POST /api/platform/secrets value=s3cr3t-new",
		"PUT /api/platform/secrets/erp-db value=s3cr3t-rot",
		"DELETE /api/platform/secrets/erp-db",
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %q in:\n%s", want, joined)
		}
	}
	if len(masked) != 3 || masked[0] || !masked[1] || !masked[2] {
		t.Errorf("masked = %v, want the name in clear and both values masked", masked)
	}
	_, body, _ := s.SelectedDescribe()
	everything := strings.Join(said, "|") + body
	if !strings.Contains(everything, "[Delete]") {
		t.Errorf("Del's question is not answered by Delete: %q", said)
	}
	if strings.Contains(everything, "s3cr3t") {
		t.Errorf("a secret value reached the screen: %q", everything)
	}
}

// UPDATED and ROTATED read like every other page's time cells, not as the hub's raw instant (#1175).
func TestSecrets_timesReadLikeEveryOtherPage(t *testing.T) {
	const updated, rotated = "2026-09-10T07:54:36.000743Z", "2026-09-11T08:00:00Z"
	s := NewSecrets(nil, nil, nil, nil)
	s.renderList([]client.SecretMeta{{Name: "erp-db", UpdatedAt: updated, RotatedAt: rotated}}, nil)
	for col, iso := range map[int]string{2: updated, 3: rotated} {
		if got, want := s.table.GetCell(1, col).Text, output.HumanTimeStr(iso); got != want {
			t.Errorf("column %d = %q, want %q", col, got, want)
		}
	}
}
