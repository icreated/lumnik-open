package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
)

// queryHub is a fake hub for `lm search query`: the search door itself, the scope-bound index
// registry (the "why zero hits?" diagnosis) and the integrator-only status door.
type queryHub struct {
	mu           sync.Mutex
	search       func(w http.ResponseWriter, r *http.Request)
	indexes      string            // body of GET /api/search-indexes ("" → 500)
	status       map[string]string // index name → body of its status door (absent → 403)
	indexesAsked bool
	statusAt     []string // status doors asked
	seenURLs     []string // raw query string of every GET /api/search
}

func (h *queryHub) serve(t *testing.T) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.mu.Lock()
		defer h.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/search":
			h.seenURLs = append(h.seenURLs, r.URL.RawQuery)
			h.search(w, r)
		case r.Method == http.MethodGet && r.URL.Path == "/api/search-indexes":
			h.indexesAsked = true
			if h.indexes == "" {
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			_, _ = fmt.Fprint(w, h.indexes)
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/platform/search/index/") && strings.HasSuffix(r.URL.Path, "/status"):
			name := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/api/platform/search/index/"), "/status")
			h.statusAt = append(h.statusAt, name)
			body, ok := h.status[name]
			if !ok {
				w.WriteHeader(http.StatusForbidden)
				_, _ = fmt.Fprint(w, `{"error":"missing role"}`)
				return
			}
			_, _ = fmt.Fprint(w, body)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

const hitsBody = `{
  "hits":[
    {"entity":"PoolProduct","key":{"product_id":"1018050"},"score":0.78,
     "fields":{"reference":"Pompe Filtra N","description":"Pompe de filtration bassin Filtra N","active":"Y"},
     "highlights":{"description":["<b>Pompe</b> de filtration bassin <b>Filtra</b> N"]},
     "canonicalLink":"/api/entities/PoolProduct?product_id=1018050"},
    {"entity":"PoolProduct","key":{"product_id":"1035439"},"score":0.35,
     "fields":{"reference":"Filtration","description":"","active":"Y"},
     "highlights":{},"canonicalLink":"/api/entities/PoolProduct?product_id=1035439"}
  ],
  "facets":{"active":[{"value":"Y","count":2},{"value":"N","count":1}],"category_id":[{"value":"1000021","count":2}]},
  "nextCursor":"CURSOR-ABC","asOf":"2026-10-05T15:04:49.072145Z"}`

// validPoolIndex is one declared, valid index over PoolProduct, as GET /api/search-indexes lists it.
const validPoolIndex = `[{"name":"pool-products","entity":"PoolProduct","language":"french","status":"valid","invalidReason":null}]`

// statusJSON is one index's status door body: liveGeneration "" means never built, run "" means no active run.
func statusJSON(liveGeneration, runPhase string) string {
	live, run := "null", ""
	if liveGeneration != "" {
		live = `"` + liveGeneration + `"`
	}
	if runPhase != "" {
		run = `,"activeRun":{"runId":"11111111-1111-1111-1111-111111111111","phase":"` + runPhase + `","startedAt":"2026-10-05T08:00:00Z"}`
	}
	return `{"name":"pool-products","status":"ready","liveGeneration":` + live + `,"documentCount":null,` +
		`"documentCountStale":false,"documentCountAsOf":null,"lagSeconds":0,"invalid":false` + run + `}`
}

// failWith is a hub whose search door answers every request with this status and body.
func failWith(status int, body string, headers ...string) *queryHub {
	return &queryHub{search: func(w http.ResponseWriter, _ *http.Request) {
		for i := 0; i+1 < len(headers); i += 2 {
			w.Header().Set(headers[i], headers[i+1])
		}
		w.WriteHeader(status)
		_, _ = fmt.Fprint(w, body)
	}}
}

func assertContainsAll(t *testing.T, where, got string, wants ...string) {
	t.Helper()
	for _, want := range wants {
		if !strings.Contains(got, want) {
			t.Errorf("%s lacks %q: %q", where, want, got)
		}
	}
}

func okSearch(body string) func(http.ResponseWriter, *http.Request) {
	return func(w http.ResponseWriter, _ *http.Request) { _, _ = fmt.Fprint(w, body) }
}

func TestSearchQuery_encodesTheRequestAndRendersHitsHighlightsFacetsAndTheNextPage(t *testing.T) {
	h := &queryHub{search: okSearch(hitsBody)}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "query", "pompe & filtra é",
		"--entity", "PoolProduct", "--facet", "active=Y", "--facet", "category_id=100 0021", "--limit", "5", "--cursor", "C0")
	if err != nil {
		t.Fatalf("query: %v", err)
	}

	// What reached the hub: parsed, so the assertion is on meaning and not on one encoding.
	if len(h.seenURLs) != 1 {
		t.Fatalf("hub asked %d times, want 1", len(h.seenURLs))
	}
	q := parseQuery(t, h.seenURLs[0])
	for k, want := range map[string]string{
		"q": "pompe & filtra é", "entity": "PoolProduct", "limit": "5", "cursor": "C0",
		"facet.active": "Y", "facet.category_id": "100 0021",
	} {
		if got := q.Get(k); got != want {
			t.Errorf("param %s = %q, want %q (raw: %s)", k, got, want, h.seenURLs[0])
		}
	}

	for _, want := range []string{
		"PoolProduct", "product_id=1018050", "0.78", "Pompe Filtra N",
		"*Pompe* de filtration bassin *Filtra* N", // highlight rendered, not raw <b> markup
		"active", "Y (2)", "N (1)", "category_id", "1000021 (2)", // facets block
		"as of",                                                     // footer
		`--cursor CURSOR-ABC`, `lm search query 'pompe & filtra é'`, // the exact way to the next page
		"--entity PoolProduct", "--facet 'active=Y'", "--limit 5",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "<b>") {
		t.Errorf("raw highlight markup leaked:\n%s", out)
	}
}

func TestSearchQuery_theMatchedFieldLeadsTheLine(t *testing.T) {
	srv := (&queryHub{search: okSearch(hitsBody)}).serve(t)
	out, _, err := runLm(t, srv, "search", "query", "pompe")
	if err != nil {
		t.Fatal(err)
	}
	line := ""
	for _, l := range strings.Split(out, "\n") {
		if strings.Contains(l, "1018050") {
			line = l
		}
	}
	// description carries the highlight, so it comes before the alphabetically earlier active/reference.
	if di, ai := strings.Index(line, "description="), strings.Index(line, "active="); di < 0 || ai < 0 || di > ai {
		t.Errorf("the highlighted field should lead the MATCH column: %q", line)
	}
}

func TestSearchQuery_noLimitFlagMeansTheHubsDefaultAndNoCursorMeansNoNextHint(t *testing.T) {
	body := strings.Replace(hitsBody, `"nextCursor":"CURSOR-ABC"`, `"nextCursor":null`, 1)
	h := &queryHub{search: okSearch(body)}
	srv := h.serve(t)

	out, _, err := runLm(t, srv, "search", "query", "pompe")
	if err != nil {
		t.Fatal(err)
	}
	q := parseQuery(t, h.seenURLs[0])
	for _, k := range []string{"limit", "cursor", "entity"} {
		if q.Has(k) {
			t.Errorf("%s was sent although its flag was not set: %s", k, h.seenURLs[0])
		}
	}
	if strings.Contains(out, "--cursor") {
		t.Errorf("a last page must not suggest a next one:\n%s", out)
	}
}

func TestSearchQuery_jsonIsTheHubsBodyAndHintsStayOffStdout(t *testing.T) {
	h := &queryHub{search: okSearch(`{"hits":[],"facets":{},"nextCursor":null,"asOf":null}`), indexes: `[]`}
	srv := h.serve(t)

	out, errOut, err := runLm(t, srv, "search", "query", "pompe", "--entity", "PoolProduct", "-o", "json")
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		Hits       []any          `json:"hits"`
		Facets     map[string]any `json:"facets"`
		NextCursor *string        `json:"nextCursor"`
		AsOf       *string        `json:"asOf"`
	}
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatalf("stdout is not the JSON body: %v\n%s", err, out)
	}
	if got.Hits == nil || len(got.Hits) != 0 {
		t.Errorf("hits must be an empty array, got %#v", got.Hits)
	}
	// The diagnosis is for a human: stderr, so a script piping stdout reads clean JSON.
	if !strings.Contains(errOut, "no SearchIndex is declared over PoolProduct") {
		t.Errorf("zero hits with -o json should still explain itself on stderr, got %q", errOut)
	}
}

// "Not set" and "set to 0" are different things: the hub owns the 1-100 bound, so a limit the user typed
// reaches it as typed — and its refusal reaches the user — instead of silently becoming the default.
func TestSearchQuery_aLimitTheUserTypedIsForwardedAsTypedEvenWhenTheHubWillRefuseIt(t *testing.T) {
	for _, typed := range []string{"0", "-3", "500"} {
		t.Run(typed, func(t *testing.T) {
			h := failWith(http.StatusBadRequest, `{"error":"limit must be between 1 and 100"}`)
			_, _, err := runLm(t, h.serve(t), "search", "query", "pompe", "--limit", typed)
			if err == nil || !strings.Contains(err.Error(), "limit must be between 1 and 100") {
				t.Fatalf("err = %v, want the hub's own refusal", err)
			}
			if len(h.seenURLs) != 1 || parseQuery(t, h.seenURLs[0]).Get("limit") != typed {
				t.Errorf("--limit %s was not forwarded as typed: %v", typed, h.seenURLs)
			}
		})
	}
}

func TestSearchQuery_aFacetThatIsNotFieldEqualsValueIsRefusedBeforeAnyRequest(t *testing.T) {
	h := &queryHub{search: okSearch(hitsBody)}
	srv := h.serve(t)
	for _, bad := range []string{"active", "=Y", "active="} {
		_, _, err := runLm(t, srv, "search", "query", "pompe", "--facet", bad)
		if err == nil || !strings.Contains(err.Error(), "field=value") {
			t.Errorf("--facet %q: err = %v, want a field=value message", bad, err)
		}
	}
	if len(h.seenURLs) != 0 {
		t.Errorf("a malformed facet reached the hub: %v", h.seenURLs)
	}
}

func TestSearchQuery_needsExactlyOneQuery(t *testing.T) {
	srv := (&queryHub{search: okSearch(hitsBody)}).serve(t)
	if _, _, err := runLm(t, srv, "search", "query"); err == nil {
		t.Error("no query: expected an error")
	}
	_, _, err := runLm(t, srv, "search", "query", "a", "b")
	if err == nil {
		t.Fatal("two arguments (a phrase the shell split): expected an error")
	}
	// Quotes are for the shell and only needed when there are spaces; there is no phrase search to ask for.
	assertContainsAll(t, "refusal", err.Error(), "ONE argument", "quote it when it has spaces", "got 2 arguments")
	for _, notWanted := range []string{"quoted phrase", "quoted query", "pompe"} {
		if strings.Contains(err.Error(), notWanted) {
			t.Errorf("refusal still says %q: %v", notWanted, err)
		}
	}
}

func TestSearchQuery_theHubsRefusalsKeepTheirMeaning(t *testing.T) {
	for _, tc := range []struct {
		name, body, want string
		status           int
	}{
		{"validation", `{"error":"q must be 1-200 characters"}`, "q must be 1-200 characters", http.StatusBadRequest},
		{"scope", `{"error":"scope not granted"}`, "scope not granted", http.StatusForbidden},
		{"cursor", `{"error":"eligible SearchIndex set changed since this cursor was issued"}`, "eligible SearchIndex set changed", http.StatusBadRequest},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, _, err := runLm(t, failWith(tc.status, tc.body).serve(t), "search", "query", "x")
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to carry %q", err, tc.want)
			}
		})
	}
}

func TestSearchQuery_noSearchEngineBuiltInSaysHowToGetOne(t *testing.T) {
	h := failWith(http.StatusNotImplemented, `{"error":"search not installed"}`)
	_, _, err := runLm(t, h.serve(t), "search", "query", "x")
	if err == nil {
		t.Fatal("expected an error")
	}
	assertContainsAll(t, "501 message", err.Error(), "not installed", "no Search provider", "--search", "--search-cluster", "never")
	if h.indexesAsked || len(h.statusAt) != 0 {
		t.Error("an unavailable engine must not trigger the zero-hit diagnosis")
	}
}

func TestSearchQuery_aDegradedEngineNamesItsReasonAndWhenToRetry(t *testing.T) {
	for reason, want := range map[string]string{
		"backend_unreachable":      "cannot be reached",
		"backend_auth_failed":      "refused the hub's credentials",
		"unsupported_distribution": "distribution the provider does not support",
		"cluster_red":              "red",
		"something_new":            "something_new", // an unknown reason is shown as is, never dropped
	} {
		t.Run(reason, func(t *testing.T) {
			h := failWith(http.StatusServiceUnavailable, fmt.Sprintf(`{"error":"search degraded","reason":%q}`, reason),
				"Retry-After", "60")
			_, _, err := runLm(t, h.serve(t), "search", "query", "x")
			if err == nil {
				t.Fatal("expected an error")
			}
			assertContainsAll(t, "503 message", err.Error(), "degraded", want, "60")
			if strings.Contains(err.Error(), "0 hits") {
				t.Errorf("a degraded engine must never read as zero hits: %v", err)
			}
		})
	}
}

const noHits = `{"hits":[],"facets":{},"nextCursor":null,"asOf":null}`

func TestSearchQuery_zeroHitsSaysWhyWhenNothingIsDeclaredOverTheEntity(t *testing.T) {
	h := &queryHub{search: okSearch(noHits), indexes: `[{"name":"other","entity":"Other","language":"french","status":"valid","invalidReason":null}]`}
	out, errOut, err := runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "PoolProduct")
	if err != nil {
		t.Fatalf("zero hits is an answer, not a failure: %v", err)
	}
	if !strings.Contains(out, "0 hits") {
		t.Errorf("stdout should say 0 hits: %q", out)
	}
	assertContainsAll(t, "diagnosis", errOut, "no SearchIndex is declared over PoolProduct", "lm search apply -f")
}

func TestSearchQuery_zeroHitsWithoutAnEntityAndNoIndexAtAll(t *testing.T) {
	h := &queryHub{search: okSearch(noHits), indexes: `[]`}
	_, errOut, err := runLm(t, h.serve(t), "search", "query", "pompe")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(errOut, "no SearchIndex is declared") || !strings.Contains(errOut, "lm search apply -f") {
		t.Errorf("diagnosis = %q", errOut)
	}
}

func TestSearchQuery_zeroHitsOverAnInvalidIndexQuotesItsReason(t *testing.T) {
	h := &queryHub{search: okSearch(noHits), indexes: `[{"name":"pool-products","entity":"PoolProduct","language":"french","status":"invalid","invalidReason":"'city' is not a canonical column of entity 'PoolProduct'"}]`}
	_, errOut, _ := runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "poolproduct")
	assertContainsAll(t, "diagnosis", errOut, "pool-products", "invalid", "'city' is not a canonical column", "lm search apply -f")
}

func TestSearchQuery_zeroHitsOverAWithheldIndexSaysItServesAgainOnceRebuilt(t *testing.T) {
	h := &queryHub{search: okSearch(noHits), indexes: `[{"name":"pool-products","entity":"PoolProduct","language":"french","status":"valid","invalidReason":null,"withheld":true}]`}
	_, errOut, _ := runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "PoolProduct")
	assertContainsAll(t, "diagnosis", errOut, "pool-products", "withheld", "removed", "lm search rebuild pool-products --wait")
}

func TestSearchQuery_zeroHitsOverAnIndexThatWasNeverBuiltSaysToRebuild(t *testing.T) {
	h := &queryHub{
		search:  okSearch(noHits),
		indexes: validPoolIndex,
		status:  map[string]string{"pool-products": statusJSON("", "")},
	}
	_, errOut, _ := runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "PoolProduct")
	assertContainsAll(t, "diagnosis", errOut, "pool-products", "not built", "lm search rebuild pool-products --wait")
}

// A first build that failed has no live generation and no active run — the very shape of "never built".
// Sending the reader to repeat the rebuild that just failed would be wrong: the message names the failure.
func TestSearchQuery_zeroHitsOverAnIndexWhoseFirstBuildFailedSaysSoAndNamesWhy(t *testing.T) {
	failed := `{"name":"pool-products","status":"degraded","liveGeneration":null,"documentCount":null,` +
		`"documentCountStale":true,"documentCountAsOf":null,"lagSeconds":2,"invalid":false,` +
		`"lastRebuild":{"runId":"11111111-1111-1111-1111-111111111111","outcome":"failed","finishedAt":"2026-10-05T08:05:00Z"},` +
		`"lastError":"IllegalStateException: entity 'PoolProduct' cannot be indexed: apply it again (lm entity apply -f <manifest>), then rebuild"}`
	h := &queryHub{search: okSearch(noHits), indexes: validPoolIndex, status: map[string]string{"pool-products": failed}}
	_, errOut, err := runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "PoolProduct")
	if err != nil {
		t.Fatal(err)
	}
	assertContainsAll(t, "diagnosis", errOut, "pool-products", "last build failed", "apply it again", "lm search status pool-products")
	if strings.Contains(errOut, "not built yet") {
		t.Errorf("a failed build is not 'not built yet': %q", errOut)
	}
}

func TestSearchQuery_zeroHitsOverAnIndexBeingBuiltSaysSo(t *testing.T) {
	h := &queryHub{
		search:  okSearch(noHits),
		indexes: validPoolIndex,
		status:  map[string]string{"pool-products": statusJSON("", "scanning")},
	}
	_, errOut, _ := runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "PoolProduct")
	if !strings.Contains(errOut, "being built") || !strings.Contains(errOut, "scanning") {
		t.Errorf("diagnosis = %q", errOut)
	}
}

// A rebuild of a built index keeps answering from its live generation: an empty answer is then a real "no
// match", and telling the reader to wait for a build that is not holding anything back would mislead.
func TestSearchQuery_zeroHitsWhileABuiltIndexIsBeingRebuiltIsNotBlamedOnTheBuild(t *testing.T) {
	h := &queryHub{
		search:  okSearch(noHits),
		indexes: validPoolIndex,
		status:  map[string]string{"pool-products": statusJSON("gen-7", "scanning")},
	}
	_, errOut, err := runLm(t, h.serve(t), "search", "query", "zzzz", "--entity", "PoolProduct")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(errOut) != "" {
		t.Errorf("a built index being rebuilt must not be reported as unbuilt, got %q", errOut)
	}
}

func TestSearchQuery_zeroHitsOverABuiltIndexIsJustNoMatch(t *testing.T) {
	h := &queryHub{
		search:  okSearch(noHits),
		indexes: validPoolIndex,
		status:  map[string]string{"pool-products": statusJSON("gen-7", "")},
	}
	out, errOut, err := runLm(t, h.serve(t), "search", "query", "zzzz", "--entity", "PoolProduct")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "0 hits") {
		t.Errorf("stdout = %q", out)
	}
	if strings.TrimSpace(errOut) != "" {
		t.Errorf("a built index with no match needs no diagnosis, got %q", errOut)
	}
}

func TestSearchQuery_theDiagnosisIsBestEffort(t *testing.T) {
	// The registry door fails, and for a non-integrator the status door is refused: zero hits still
	// answers plainly, exit 0, and says nothing it cannot back.
	h := &queryHub{search: okSearch(noHits), indexes: ""}
	out, errOut, err := runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "PoolProduct")
	if err != nil {
		t.Fatalf("a failing diagnosis must not fail the query: %v", err)
	}
	if !strings.Contains(out, "0 hits") || strings.Contains(errOut, "no SearchIndex is declared") {
		t.Errorf("out=%q err=%q", out, errOut)
	}

	// Status refused (403) for a plain lm_user: the index is valid, so no claim about the build.
	h = &queryHub{search: okSearch(noHits),
		indexes: validPoolIndex}
	_, errOut, err = runLm(t, h.serve(t), "search", "query", "pompe", "--entity", "PoolProduct")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(errOut, "not built") || strings.Contains(errOut, "being built") {
		t.Errorf("without the status door the CLI cannot know the build state, but claimed: %q", errOut)
	}
}

// A closed pipe (`lm search query … | head`) or a full disk must not read as success: the table is the
// answer, and an answer that was not written is a failure.
type brokenWriter struct{}

func (brokenWriter) Write([]byte) (int, error) { return 0, errors.New("write failed: broken pipe") }

func TestSearchQuery_aFailedWriteOfTheTableIsAFailedCommand(t *testing.T) {
	srv := (&queryHub{search: okSearch(hitsBody)}).serve(t)
	setupCfg(t, srv.URL)
	root := NewRootCmd()
	root.SetArgs([]string{"search", "query", "pompe"})
	root.SetOut(brokenWriter{})
	root.SetErr(&strings.Builder{})
	err := root.Execute()
	if err == nil || !strings.Contains(err.Error(), "broken pipe") {
		t.Fatalf("err = %v, want the write failure", err)
	}
}

func TestSearchQuery_sanitizesWhatTheHubEchoesIntoTheTerminal(t *testing.T) {
	body := strings.Replace(hitsBody, `"reference":"Pompe Filtra N"`, `"reference":"Pompe `+bidiName+`"`, 1)
	srv := (&queryHub{search: okSearch(body)}).serve(t)
	withConfig(t, srv.URL)

	out, errb, code := runCmd(t, "search", "query", "pompe")
	if code != 0 {
		t.Fatalf("exit=%d stderr=%q", code, errb)
	}
	assertSanitized(t, "search query stdout", out)
}

func parseQuery(t *testing.T, raw string) url.Values {
	t.Helper()
	v, err := url.ParseQuery(raw)
	if err != nil {
		t.Fatalf("hub received an unparseable query %q: %v", raw, err)
	}
	return v
}

func TestSearchQuery_theFooterCountsOneHitInTheSingular(t *testing.T) {
	one := strings.Replace(hitsBody, `,
    {"entity":"PoolProduct","key":{"product_id":"1035439"},"score":0.35,
     "fields":{"reference":"Filtration","description":"","active":"Y"},
     "highlights":{},"canonicalLink":"/api/entities/PoolProduct?product_id=1035439"}`, "", 1)
	srv := (&queryHub{search: okSearch(one)}).serve(t)
	out, _, err := runLm(t, srv, "search", "query", "pompe")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "1 hit shown") {
		t.Errorf("one hit should read \"1 hit shown\":\n%s", out)
	}
}
