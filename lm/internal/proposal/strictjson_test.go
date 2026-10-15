package proposal

import (
	"errors"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParseStrict(t *testing.T) {
	cases := []struct {
		name string
		src  string
		want error
	}{
		{"object", `{"a":1,"b":[true,null,"x"]}`, nil},
		{"duplicate key", `{"a":1,"a":2}`, errDuplicateKey},
		{"duplicate key, escaped spelling", `{"a":1,"\u0061":2}`, errDuplicateKey},
		{"duplicate key, nested", `{"o":{"a":1,"a":2}}`, errDuplicateKey},
		{"duplicate key, inside an array element", `{"l":[{"a":1,"a":2}]}`, errDuplicateKey},
		{"trailing second value", `{"a":1} {}`, errMalformed},
		{"trailing garbage", `{"a":1} x`, errMalformed},
		{"truncated", `{"a":`, errMalformed},
		{"empty", ``, errMalformed},
		{"non-string key", `{1:2}`, errMalformed},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			n, err := parseStrict(c.src)
			if c.want == nil {
				require.NoError(t, err)
				require.NotNil(t, n)
				return
			}
			assert.True(t, errors.Is(err, c.want), "got %v, want %v", err, c.want)
		})
	}
}

func TestParseStrict_tree(t *testing.T) {
	n, err := parseStrict(`{"a":"x","n":12,"l":[1,2],"o":{"k":null}}`)
	require.NoError(t, err)
	assert.Equal(t, kString, n.get("a").kind)
	assert.Equal(t, "x", n.get("a").str)
	assert.Equal(t, kNumber, n.get("n").kind)
	assert.Equal(t, "12", n.get("n").str)
	assert.Len(t, n.get("l").items, 2)
	assert.Equal(t, kNull, n.get("o").get("k").kind)
	assert.Nil(t, n.get("absent"))
}

// Jackson (the hub) and the site refuse a header nested more than 1000 levels deep, the root
// object being level 1; the corpus pins it (malformed-header--too-deep).
func TestParseStrict_nestingCapIsJacksonsAndTheSites(t *testing.T) {
	nested := func(levels int) string {
		return `{"x":` + strings.Repeat("[", levels) + strings.Repeat("]", levels) + `}`
	}
	_, err := parseStrict(nested(999)) // root + 999 arrays = level 1000
	assert.NoError(t, err)
	_, err = parseStrict(nested(1000)) // level 1001
	assert.True(t, errors.Is(err, errMalformed))
}

// The three other StreamReadConstraints defaults of Jackson 2.21.5, measured on the hub's own
// parser: a header past any of them is refused there, so it is refused here.
func TestParseStrict_lengthCapsAreJacksons(t *testing.T) {
	astral := string(rune(0x1F600)) // two UTF-16 units, as Jackson counts it
	cases := []struct {
		name string
		src  func() string // built inside its subtest: the 20 MB payloads must not all be alive at once
		want error
	}{
		{"string at the cap", func() string { return `"` + strings.Repeat("a", 20_000_000) + `"` }, nil},
		{"string over the cap", func() string { return `"` + strings.Repeat("a", 20_000_001) + `"` }, errMalformed},
		{"string counted in UTF-16 units, at the cap", func() string { return `"` + strings.Repeat(astral, 10_000_000) + `"` }, nil},
		{"string counted in UTF-16 units, over the cap", func() string { return `"` + strings.Repeat(astral, 10_000_001) + `"` }, errMalformed},
		{"string counted after decoding, over the cap", func() string { return `"` + strings.Repeat(`\n`, 20_000_001) + `"` }, errMalformed},
		{"name at the cap", func() string { return `{"` + strings.Repeat("a", 50_000) + `":1}` }, nil},
		{"name over the cap", func() string { return `{"` + strings.Repeat("a", 50_001) + `":1}` }, errMalformed},
		{"integer at the cap", func() string { return "1" + strings.Repeat("0", 999) }, nil},
		{"integer over the cap", func() string { return "1" + strings.Repeat("0", 1000) }, errMalformed},
		{"negative integer: the sign is not a digit", func() string { return "-1" + strings.Repeat("0", 999) }, nil},
		{"negative integer over the cap", func() string { return "-1" + strings.Repeat("0", 1000) }, errMalformed},
		// A float is capped on the sum of its integer, fraction and exponent digits (signs are not
		// digits, a lone leading zero counts for none). Each pair is the last length the hub's
		// parser accepts and the first it refuses, measured with the number inside an array.
		{"fraction after a lone zero, at the cap", func() string { return "0." + strings.Repeat("1", 1000) }, nil},
		{"fraction after a lone zero, over the cap", func() string { return "0." + strings.Repeat("1", 1001) }, errMalformed},
		{"negative fraction after a lone zero, at the cap", func() string { return "-0." + strings.Repeat("1", 1000) }, nil},
		{"negative fraction after a lone zero, over the cap", func() string { return "-0." + strings.Repeat("1", 1001) }, errMalformed},
		{"integer part and a fraction, at the cap", func() string { return strings.Repeat("1", 999) + ".5" }, nil},
		{"integer part and a fraction, over the cap", func() string { return strings.Repeat("1", 1000) + ".5" }, errMalformed},
		{"integer part and an exponent, at the cap", func() string { return strings.Repeat("1", 999) + "e5" }, nil},
		{"integer part and an exponent, over the cap", func() string { return strings.Repeat("1", 1000) + "e5" }, errMalformed},
		{"exponent, at the cap", func() string { return "1e" + strings.Repeat("1", 999) }, nil},
		{"exponent, over the cap", func() string { return "1e" + strings.Repeat("1", 1000) }, errMalformed},
		{"a signed exponent: its sign is not a digit, at the cap", func() string { return "1e-" + strings.Repeat("1", 999) }, nil},
		{"a signed exponent, over the cap", func() string { return "1e+" + strings.Repeat("1", 1000) }, errMalformed},
		{"all three parts, at the cap", func() string { return "1.5e" + strings.Repeat("1", 998) }, nil},
		{"all three parts, over the cap", func() string { return "1.5e" + strings.Repeat("1", 999) }, errMalformed},
		{"all three parts, fraction long, at the cap", func() string { return "1." + strings.Repeat("1", 998) + "e1" }, nil},
		{"all three parts, fraction long, over the cap", func() string { return "1." + strings.Repeat("1", 999) + "e1" }, errMalformed},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := parseStrict(c.src())
			assert.ErrorIs(t, err, c.want)
		})
	}
}
