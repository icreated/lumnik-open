package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/config"
	"github.com/stretchr/testify/assert"
)

func TestFlattenRowsPreservesColumnOrder(t *testing.T) {
	var records []EntityRow
	if err := json.Unmarshal([]byte(`[{"documentno":"SO-9","total":312480,"paid":null},{"paid":true,"documentno":"SO-10","total":5}]`), &records); err != nil {
		t.Fatal(err)
	}
	cols, rows := flattenRows(records)

	want := []string{"documentno", "total", "paid"}
	if len(cols) != len(want) || cols[0] != want[0] || cols[1] != want[1] || cols[2] != want[2] {
		t.Fatalf("columns = %v, want %v", cols, want)
	}
	if len(rows) != 2 {
		t.Fatalf("rows = %d, want 2", len(rows))
	}
	if rows[0][0] != "SO-9" || rows[0][1] != "312480" || rows[0][2] != "" {
		t.Errorf("row0 = %v, want [SO-9 312480 <empty>]", rows[0])
	}
	if rows[1][2] != "true" {
		t.Errorf("row1 paid = %q, want true", rows[1][2])
	}
}

// The hub sends "retained": null when the fused record holds no value (#883): it must not read as "".
func TestDisputedKeepsANullRetainedDistinctFromEmpty(t *testing.T) {
	var got []Disputed
	if err := json.Unmarshal([]byte(`[{"key":"P-1002","retained":null},{"key":"P-1003","retained":""},{"key":"P-1008","retained":"Lyon"}]`), &got); err != nil {
		t.Fatal(err)
	}
	assert.Equal(t, []*string{nil, StrPtr(""), StrPtr("Lyon")},
		[]*string{got[0].Retained, got[1].Retained, got[2].Retained})
}

func TestRagAskSendsAnalyticMode(t *testing.T) {
	var gotMode, gotScope string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		gotMode, _ = body["mode"].(string)
		gotScope, _ = body["scope"].(string)
		_, _ = w.Write([]byte(`{"answer":"47 orders worth €312,480 never invoiced.","sql":"SELECT documentno, amount FROM connector.t_order LIMIT 500","rowCount":47,"rows":[{"documentno":"SO-9","amount":312480}],"dataAsOf":"2026-08-20T06:05:00Z"}`))
	}))
	defer srv.Close()

	c := New(config.Context{Server: srv.URL})
	res, err := c.RagAsk("which orders were never invoiced?", "atelier", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if gotMode != "analytic" {
		t.Errorf("mode sent = %q, want analytic", gotMode)
	}
	if gotScope != "atelier" {
		t.Errorf("scope sent = %q, want atelier", gotScope)
	}
	if res.RowCount != 47 {
		t.Errorf("rowCount = %d, want 47", res.RowCount)
	}
	if len(res.Columns) != 2 || res.Columns[0] != "documentno" || res.Columns[1] != "amount" {
		t.Errorf("columns = %v, want [documentno amount]", res.Columns)
	}
	if res.DataAsOf != "2026-08-20T06:05:00Z" {
		t.Errorf("dataAsOf = %q, want the run timestamp", res.DataAsOf)
	}
	if len(res.Rows) != 1 || res.Rows[0][1] != "312480" {
		t.Errorf("rows = %v, want [[SO-9 312480]]", res.Rows)
	}
}

// #1105: a result with no rows and no freshness is still a whole document: [] where a list is empty,
// null where the hub sends null — a consumer never has to tell an absent key from an empty answer.
func TestAskResult_aDocumentWithNoRowsKeepsEveryKey(t *testing.T) {
	js, err := json.Marshal(AskResult{Answer: "Je ne peux pas répondre.", Abstained: true})
	if err != nil {
		t.Fatal(err)
	}
	assert.Equal(t, `{"answer":"Je ne peux pas répondre.","sql":"","rowCount":0,"columns":[],"rows":[],`+
		`"dataAsOf":null,"otherDoors":[],"disputed":[],"disputedTruncated":false,"abstained":true,"understoodAs":null}`, string(js))
}

// A jsonb cell is shown as JSON, with its characters as written: & < > are not turned into \u0026 escapes.
func TestFlattenRowsShowsANestedValueAsWritten(t *testing.T) {
	var records []EntityRow
	if err := json.Unmarshal([]byte(`[{"address":{"street":"1 rue A & B <C>"},"tags":["x&y"]}]`), &records); err != nil {
		t.Fatal(err)
	}
	_, rows := flattenRows(records)
	assert.Equal(t, []string{`{"street":"1 rue A & B <C>"}`, `["x&y"]`}, rows[0])
}
