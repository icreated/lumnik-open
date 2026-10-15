// Package proposal reads a proposal dossier (schema 1) and writes the start of a manifest from it.
// It is deliberately offline: no HTTP, no client. The contract is the corpus under
// lumnik-hub/src/test/resources/proposal/fixtures/, which judges this reader, the hub's and the
// site's alike.
package proposal

import (
	"encoding/json"
	"errors"
	"io"
	"strings"
)

var (
	errDuplicateKey = errors.New("duplicate key")
	errMalformed    = errors.New("malformed JSON")
)

// maxDepth is Jackson's default nesting cap, which the hub inherits and the site mirrors: the root
// object is level 1. A header nested deeper is refused, not read.
const maxDepth = 1000

// The other length caps Jackson 2.21.5 applies by default (StreamReadConstraints.defaults(), which
// the hub inherits): a string value, an object key and a number's digits. Jackson counts UTF-16
// units of the decoded text, and a number's digits without its signs. They move only with Jackson.
const (
	maxStringLength = 20_000_000
	maxNameLength   = 50_000
	maxNumberLength = 1000 // an integer's digits; a float's integer, fraction and exponent digits together
)

// utf16Len is the length Jackson would count for s.
func utf16Len(s string) int {
	n := 0
	for _, r := range s {
		n++
		if r >= 0x10000 {
			n++
		}
	}
	return n
}

type kind int

const (
	kNull kind = iota
	kBool
	kNumber
	kString
	kArray
	kObject
)

// node is one JSON value. Numbers keep their literal (str) so an integer is told from 1.0.
type node struct {
	kind  kind
	str   string
	items []*node
	vals  map[string]*node
}

func (n *node) get(key string) *node {
	if n == nil || n.kind != kObject {
		return nil
	}
	return n.vals[key]
}

// parseStrict reads exactly one JSON value. encoding/json tolerates a duplicate key (the last wins),
// which is the one thing the contract forbids, so the token stream is walked here; the key compared
// is the decoded one, so a key written as "a" collides with the same key written as "\u0061".
func parseStrict(src string) (*node, error) {
	dec := json.NewDecoder(strings.NewReader(src))
	dec.UseNumber()
	n, err := value(dec, 0)
	if err != nil {
		return nil, err
	}
	if _, err := dec.Token(); err != io.EOF {
		return nil, errMalformed
	}
	return n, nil
}

func value(dec *json.Decoder, depth int) (*node, error) {
	tok, err := dec.Token()
	if err != nil {
		return nil, errMalformed
	}
	switch t := tok.(type) {
	case json.Delim:
		if depth+1 > maxDepth {
			return nil, errMalformed
		}
		if t == '{' {
			return object(dec, depth+1)
		}
		if t == '[' {
			return array(dec, depth+1)
		}
		return nil, errMalformed
	case string:
		if utf16Len(t) > maxStringLength {
			return nil, errMalformed
		}
		return &node{kind: kString, str: t}, nil
	case json.Number:
		if tooManyDigits(t.String()) {
			return nil, errMalformed
		}
		return &node{kind: kNumber, str: t.String()}, nil
	case bool:
		return &node{kind: kBool}, nil
	case nil:
		return &node{kind: kNull}, nil
	}
	return nil, errMalformed
}

func object(dec *json.Decoder, depth int) (*node, error) {
	n := &node{kind: kObject, vals: map[string]*node{}}
	for dec.More() {
		k, err := dec.Token()
		key, ok := k.(string)
		if err != nil || !ok || utf16Len(key) > maxNameLength {
			return nil, errMalformed
		}
		if _, dup := n.vals[key]; dup {
			return nil, errDuplicateKey
		}
		v, err := value(dec, depth)
		if err != nil {
			return nil, err
		}
		n.vals[key] = v
	}
	if _, err := dec.Token(); err != nil { // the closing brace
		return nil, errMalformed
	}
	return n, nil
}

func array(dec *json.Decoder, depth int) (*node, error) {
	n := &node{kind: kArray}
	for dec.More() {
		v, err := value(dec, depth)
		if err != nil {
			return nil, err
		}
		n.items = append(n.items, v)
	}
	if _, err := dec.Token(); err != nil { // the closing bracket
		return nil, errMalformed
	}
	return n, nil
}

// tooManyDigits reports a number past Jackson's cap, counted as its parser counts it: an integer by
// its digits, a float by the sum of its integer, fraction and exponent digits. A sign is not a digit
// and a lone leading zero counts for none.
func tooManyDigits(lit string) bool {
	lit = strings.TrimPrefix(lit, "-")
	mantissa, exp, _ := strings.Cut(strings.ToLower(lit), "e")
	whole, fraction, isFloat := strings.Cut(mantissa, ".")
	if !isFloat && exp == "" {
		return len(whole) > maxNumberLength
	}
	if whole == "0" {
		whole = ""
	}
	exp = strings.TrimLeft(exp, "+-")
	return len(whole)+len(fraction)+len(exp) > maxNumberLength
}
