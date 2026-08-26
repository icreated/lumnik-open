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
			Sources: []client.DetailSource{{Src: "cust_siebel", Value: "Marie"}, {Src: "t_cust_csv", Value: "MARIE A."}},
		}},
	}
	var b bytes.Buffer
	formatDetail(&b, res)
	out := b.String()
	assert.Contains(t, out, "Clients / C-200")
	assert.Contains(t, out, "Code client")
	assert.Contains(t, out, "(!) Où les sources divergent")
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
	assert.Contains(t, out, "✓ toutes les sources concordent")
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
	assert.NotContains(t, out, "concordent")
	assert.NotContains(t, out, "divergent")
}
