package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
)

// #879: the answer language comes from the calling surface — --lang, else LANG, else nothing.
func askSendsLang(t *testing.T, env string, args ...string) (lang string, sent bool) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		v, ok := body["lang"]
		sent = ok
		lang, _ = v.(string)
		_ = json.NewEncoder(w).Encode(map[string]any{"answer": "ok", "sql": "SELECT 1", "rowCount": 0, "rows": []any{}})
	}))
	defer srv.Close()
	setupCfg(t, srv.URL)
	t.Setenv("LC_ALL", "")
	t.Setenv("LC_MESSAGES", "")
	t.Setenv("LANG", env)

	cmd := NewRootCmd()
	buf := new(bytes.Buffer)
	cmd.SetOut(buf)
	cmd.SetErr(buf)
	cmd.SetArgs(append([]string{"ask", "--scope", "desk"}, append(args, "SO-000004 statut ?")...))
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	return lang, sent
}

func TestAsk_langComesFromTheEnvironment(t *testing.T) {
	if lang, _ := askSendsLang(t, "fr_FR.UTF-8"); lang != "fr" {
		t.Errorf("lang sent = %q, want fr", lang)
	}
}

func TestAsk_theLangFlagWinsOverTheEnvironment(t *testing.T) {
	if lang, _ := askSendsLang(t, "fr_FR.UTF-8", "--lang", "de"); lang != "de" {
		t.Errorf("lang sent = %q, want de", lang)
	}
}

func TestAsk_aLocaleWithoutALanguageSendsNone(t *testing.T) {
	for _, env := range []string{"C", "POSIX", "C.UTF-8", ""} {
		if _, sent := askSendsLang(t, env); sent {
			t.Errorf("LANG=%q: lang was sent, want it absent", env)
		}
	}
}

func TestAnswerLang_lcAllOutranksLang(t *testing.T) {
	t.Setenv("LC_ALL", "de_DE.UTF-8")
	t.Setenv("LC_MESSAGES", "")
	t.Setenv("LANG", "fr_FR.UTF-8")
	if got := client.AnswerLang(""); got != "de" {
		t.Errorf("AnswerLang = %q, want de (LC_ALL wins)", got)
	}
}
