package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/url"
	"slices"
	"sort"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// EntityFilter is one exact-match filter on a canonical column (GET /api/entities/{name}?col=value).
type EntityFilter struct {
	Column string
	Value  string
}

// EntityRows reads one page of an entity's canonical rows — the thin facade over
// GET /api/entities/{name}. Name, column names and values are all encoded; the paging controls
// are sent as given, so the caller decides what range it validates.
func (c *Client) EntityRows(name string, filters []EntityFilter, limit, offset int) ([]EntityRow, error) {
	q := url.Values{}
	for _, f := range filters {
		q.Add(f.Column, f.Value)
	}
	q.Set("limit", strconv.Itoa(limit))
	q.Set("offset", strconv.Itoa(offset))
	var rows []EntityRow
	if err := c.GET("/api/entities/"+url.PathEscape(name)+"?"+q.Encode(), &rows); err != nil {
		return nil, err
	}
	return rows, nil
}

// EntityRow is one canonical row with its columns in the order the hub sent them. A Go map would
// sort them, and the order is the entity's declared one — the table header and the json/yaml
// documents must read the same way the hub wrote them. Numbers stay json.Number so a wide
// identifier is printed as the digits the hub sent, never rounded through float64.
type EntityRow struct {
	cols []string
	vals map[string]any
}

// Columns returns the column names in the hub's order.
func (r EntityRow) Columns() []string { return slices.Clone(r.cols) } // a copy: the order is the row's invariant

// Cell renders one column for a table: null is empty, a string is itself, anything else its JSON
// (a json.Number prints as the digits the hub sent).
func (r EntityRow) Cell(col string) string {
	switch v := r.vals[col].(type) {
	case nil:
		return ""
	case string:
		return v
	default:
		b, _ := json.Marshal(v) // a value that just decoded from JSON marshals
		return string(b)
	}
}

// UnmarshalJSON reads an object token by token to keep its key order.
func (r *EntityRow) UnmarshalJSON(data []byte) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	if tok, err := dec.Token(); err != nil {
		return err
	} else if d, ok := tok.(json.Delim); !ok || d != '{' {
		return fmt.Errorf("entity row: want a JSON object, got %v", tok)
	}
	r.cols, r.vals = nil, map[string]any{}
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return err
		}
		key, _ := keyTok.(string)
		var v any
		if err := dec.Decode(&v); err != nil {
			return err
		}
		if _, dup := r.vals[key]; !dup {
			r.cols = append(r.cols, key)
		}
		r.vals[key] = v
	}
	_, err := dec.Token() // the closing brace
	return err
}

// MarshalJSON writes the columns back in the order they arrived.
func (r EntityRow) MarshalJSON() ([]byte, error) {
	var b bytes.Buffer
	b.WriteByte('{')
	for i, col := range r.cols {
		if i > 0 {
			b.WriteByte(',')
		}
		k, err := json.Marshal(col)
		if err != nil {
			return nil, err
		}
		v, err := json.Marshal(r.vals[col])
		if err != nil {
			return nil, err
		}
		b.Write(k)
		b.WriteByte(':')
		b.Write(v)
	}
	b.WriteByte('}')
	return b.Bytes(), nil
}

// MarshalYAML writes the columns in arrival order, numbers as numbers.
func (r EntityRow) MarshalYAML() (any, error) {
	n := &yaml.Node{Kind: yaml.MappingNode}
	for _, col := range r.cols {
		v, err := yamlValue(r.vals[col])
		if err != nil {
			return nil, err
		}
		n.Content = append(n.Content, &yaml.Node{Kind: yaml.ScalarNode, Value: col}, v)
	}
	return n, nil
}

func yamlValue(v any) (*yaml.Node, error) {
	switch t := v.(type) {
	case nil:
		return &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!null", Value: "null"}, nil
	case json.Number:
		tag := "!!int"
		if strings.ContainsAny(t.String(), ".eE") {
			tag = "!!float"
		}
		return &yaml.Node{Kind: yaml.ScalarNode, Tag: tag, Value: t.String()}, nil
	case []any:
		n := &yaml.Node{Kind: yaml.SequenceNode}
		for _, e := range t {
			c, err := yamlValue(e)
			if err != nil {
				return nil, err
			}
			n.Content = append(n.Content, c)
		}
		return n, nil
	case map[string]any:
		// json.Marshal sorts a map's keys; do the same so the output is stable.
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		n := &yaml.Node{Kind: yaml.MappingNode}
		for _, k := range keys {
			c, err := yamlValue(t[k])
			if err != nil {
				return nil, err
			}
			n.Content = append(n.Content, &yaml.Node{Kind: yaml.ScalarNode, Value: k}, c)
		}
		return n, nil
	default:
		n := &yaml.Node{}
		if err := n.Encode(v); err != nil {
			return nil, err
		}
		return n, nil
	}
}
