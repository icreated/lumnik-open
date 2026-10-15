package cli

import (
	"bytes"
	"testing"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/stretchr/testify/assert"
)

func TestFormatDetail_divergenceShowsEachSource(t *testing.T) {
	res := &client.ViewDetailResult{
		Title: "Clients", Key: "C-200",
		Fields: []client.DetailField{
			{Name: "customer_code", Label: "Code client", Value: "C-200"},
			{Name: "name", Label: "Nom", Value: "Marie"},
		},
		ConflictLedger: true,
		Conflicts: []client.DetailConflict{{
			Column: "name", Label: "Nom",
			Sources: []client.DetailSource{{Src: "cust_siebel", Value: client.StrPtr("Marie")}, {Src: "t_cust_csv", Value: client.StrPtr("MARIE A.")}},
		}},
	}
	var b bytes.Buffer
	formatDetail(&b, res)
	out := b.String()
	assert.Contains(t, out, "Clients / C-200")
	assert.Contains(t, out, "Code client")
	assert.Contains(t, out, "(!) Ambiguïtés et divergences")
	assert.Contains(t, out, "cust_siebel «Marie»")
	assert.Contains(t, out, "t_cust_csv «MARIE A.»")
	assert.Contains(t, out, " · ")
}

func TestFormatDetail_agreementShowsPositiveLine(t *testing.T) {
	res := &client.ViewDetailResult{
		Title: "Clients", Key: "C-100",
		Fields:         []client.DetailField{{Name: "name", Label: "Nom", Value: "Jean"}},
		ConflictLedger: true,
		Conflicts:      nil,
	}
	var b bytes.Buffer
	formatDetail(&b, res)
	out := b.String()
	assert.Contains(t, out, "✓ aucune ambiguïté ni divergence")
	assert.NotContains(t, out, "divergent")
}

func TestFormatDetail_noLedgerOmitsConflictSection(t *testing.T) {
	res := &client.ViewDetailResult{
		Title: "Raw", Key: "X",
		Fields:         []client.DetailField{{Name: "name", Label: "Name", Value: "Solo"}},
		ConflictLedger: false,
	}
	var b bytes.Buffer
	formatDetail(&b, res)
	out := b.String()
	assert.Contains(t, out, "Raw / X")
	assert.NotContains(t, out, "aucune ambiguïté")
	assert.NotContains(t, out, "divergent")
}

func TestFormatDetail_anEmptyLookupValueReadsEmptyNotBlankAndNotAsADisagreement(t *testing.T) {
	res := &client.ViewDetailResult{
		Title: "T", Key: "K", ConflictLedger: true,
		Conflicts: []client.DetailConflict{{Column: "category_name", Label: "Category", Sources: []client.DetailSource{
			{Src: "t_product_categories", Value: client.StrPtr("Pumps")},
			{Src: "t_product_categories", Value: nil},
			{Src: "t_product_categories", Value: client.StrPtr("")},
		}}},
	}
	var b bytes.Buffer
	formatDetail(&b, res)
	out := b.String()
	assert.Contains(t, out, "t_product_categories «Pumps»")
	assert.Contains(t, out, "t_product_categories «(empty)»")
	assert.Contains(t, out, "t_product_categories «»")
	assert.NotContains(t, out, "sources divergent")
	assert.NotContains(t, out, "Où les sources")
}
