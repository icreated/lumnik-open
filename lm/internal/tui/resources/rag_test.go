package resources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gdamore/tcell/v2"
	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/config"
)

type ragHub struct {
	modules string
	calls   []string
}

func (h *ragHub) serve(t *testing.T) *client.Client {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.calls = append(h.calls, r.Method+" "+r.URL.Path)
		switch r.URL.Path {
		case "/api/platform/modules":
			_, _ = w.Write([]byte(h.modules))
		case "/api/platform/rag/status":
			_, _ = w.Write([]byte(`{"indexed":true,"chunks":412,"indexedAt":"2026-10-16T09:12:00Z",
				"provider":"ollama","embeddingModel":"nomic-embed-text"}`))
		case "/api/platform/rag/scopes":
			_, _ = w.Write([]byte(`[{"scope":"ventes[red]","connectors":2,"chunks":1830,"lastIndexed":"2026-10-16T08:00:00Z"}]`))
		case "/api/platform/rag/reindex":
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"reindex_started"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return client.New(config.Context{Server: srv.URL})
}

func (h *ragHub) asked(path string) bool {
	for _, c := range h.calls {
		if strings.HasSuffix(c, " "+path) {
			return true
		}
	}
	return false
}

func ragText(r *Rag) string {
	var b strings.Builder
	b.WriteString(r.table.GetTitle())
	for row := 0; row < r.table.GetRowCount(); row++ {
		for col := 0; col < r.table.GetColumnCount(); col++ {
			if c := r.table.GetCell(row, col); c != nil {
				b.WriteString(" " + c.Text)
			}
		}
	}
	return b.String()
}

const (
	ragActive = `{"modules":[{"id":"core","active":true},{"id":"rag","active":true},{"id":"llm","active":true}]}`
	ragOff    = `{"modules":[{"id":"core","active":true},{"id":"rag","active":false,"inactiveReason":"disabled:lumnik.ai.enabled"}]}`
	ragAbsent = `{"modules":[{"id":"core","active":true}]}`
	ragNoLLM  = `{"modules":[{"id":"rag","active":false,"inactiveReason":"missing dependency: llm"}]}`
)

// The RAG panel shows chunk counts per scope and the platform corpus' last indexing (#1135).
func TestRag_activeShowsScopesAndThePlatformCorpus(t *testing.T) {
	h := &ragHub{modules: ragActive}
	r := NewRag(nil, h.serve(t), nil, nil)
	r.Refresh()
	got := ragText(r)
	for _, want := range []string{"ventes[red[]", "1830", "412 chunks", "ollama", "nomic-embed-text"} {
		if !strings.Contains(got, want) {
			t.Errorf("panel missing %q:\n%s", want, got)
		}
	}
}

// Switched off is not absent: the panel says which of the two applies, from the module registry,
// and asks the RAG doors nothing either way (#1135).
func TestRag_switchedOffAndAbsentAreToldApart(t *testing.T) {
	for _, c := range []struct {
		name, modules, want string
	}{
		{"switched off", ragOff, "switched off on this hub (lumnik.ai.enabled=false)"},
		{"not in the build", ragAbsent, "not in this build"},
		{"inactive otherwise", ragNoLLM, "missing dependency: llm"},
	} {
		t.Run(c.name, func(t *testing.T) {
			h := &ragHub{modules: c.modules}
			r := NewRag(nil, h.serve(t), nil, nil)
			r.Refresh()
			if got := ragText(r); !strings.Contains(got, c.want) {
				t.Errorf("panel = %q, want %q", got, c.want)
			}
			if h.asked("/api/platform/rag/status") || h.asked("/api/platform/rag/scopes") {
				t.Errorf("calls = %q: an unavailable RAG must not be asked", h.calls)
			}
			var said []string
			r.onError = func(m string) { said = append(said, m) }
			r.handleKey(tcell.NewEventKey(tcell.KeyRune, 'R', tcell.ModNone))
			if h.asked("/api/platform/rag/reindex") || len(said) != 1 {
				t.Errorf("R on an unavailable RAG: calls %q, said %q — want no call and one reason", h.calls, said)
			}
		})
	}
}

// R reindexes after a yes that names what it rebuilds; a no reaches nothing (#1135).
func TestRag_reindexAsksFirst(t *testing.T) {
	for _, answer := range []bool{false, true} {
		h := &ragHub{modules: ragActive}
		var questions, toasts []string
		r := NewRag(nil, h.serve(t), nil, func(m string) { toasts = append(toasts, m) })
		r.SetConfirm(func(q, label string, yes func()) {
			questions = append(questions, q+" ["+label+"]")
			if answer {
				yes()
			}
		})
		r.Refresh()
		r.handleKey(tcell.NewEventKey(tcell.KeyRune, 'R', tcell.ModNone))
		if len(questions) != 1 || !strings.Contains(questions[0], "platform corpus") || !strings.Contains(questions[0], "[Reindex]") {
			t.Fatalf("questions = %q, want one naming the platform corpus, answered by Reindex", questions)
		}
		if got := h.asked("/api/platform/rag/reindex"); got != answer {
			t.Errorf("answer %v: reindex reached the hub = %v", answer, got)
		}
		if answer && len(toasts) != 1 {
			t.Errorf("toasts = %q, want the reindex said", toasts)
		}
	}
}
