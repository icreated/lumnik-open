package proposal

import (
	"errors"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// Verdict is the corpus's three-way judgement of a file; only the replay below asks for it.
type Verdict int

const (
	Invalid Verdict = iota
	Valid
	// ValidRecheck is valid and carries evidence: an importer must not believe it unchecked.
	ValidRecheck
)

// Judge is the corpus's view: the verdict and, for an invalid file, the reason.
func Judge(file string) (Verdict, Reason) {
	_, recheck, err := read(file)
	var r *Refusal
	switch {
	case errors.As(err, &r):
		return Invalid, r.Reason
	case recheck:
		return ValidRecheck, ""
	}
	return Valid, ""
}

var verdictOfDir = map[string]Verdict{"valid": Valid, "valid-recheck": ValidRecheck, "invalid": Invalid}

// The corpus is the executable contract: the directory is the verdict, an invalid file's stem
// before "--" is the reason. The hub's reader and the site's are judged by these same files.
func TestCorpusReplay(t *testing.T) {
	dir := filepath.Join(repoRoot(t), "lumnik-hub/src/test/resources/proposal/fixtures")
	n := 0
	for sub, want := range verdictOfDir {
		files, err := filepath.Glob(filepath.Join(dir, sub, "*.md"))
		require.NoError(t, err)
		for _, f := range files {
			n++
			t.Run(sub+"/"+filepath.Base(f), func(t *testing.T) {
				raw, err := os.ReadFile(f)
				require.NoError(t, err)
				text, err := Decode(raw)
				require.NoError(t, err)
				got, reason := Judge(text)
				assert.Equal(t, want, got)
				if want == Invalid {
					stem := strings.TrimSuffix(filepath.Base(f), ".md")
					assert.Equal(t, Reason(strings.SplitN(stem, "--", 2)[0]), reason)
				} else {
					assert.Empty(t, reason)
				}
			})
		}
	}
	require.GreaterOrEqual(t, n, 54, "the corpus has 54 files today; a smaller replay is a vacuous one")
}

func TestDecode_refusesWhatIsNotUTF8(t *testing.T) {
	_, err := Decode([]byte("---\n{\"words\":\"caf\xe9\"}\n---\n")) // Latin-1 é
	var r *Refusal
	require.ErrorAs(t, err, &r)
	assert.Equal(t, MalformedHeader, r.Reason)
}

func TestRead_oneLeadingBOMIsIgnoredAndASecondIsNot(t *testing.T) {
	text, err := Decode([]byte("\xef\xbb\xbf---\n{}\n---\n"))
	require.NoError(t, err)
	_, err = Read(text)
	var r *Refusal
	require.ErrorAs(t, err, &r)
	assert.Equal(t, MissingField, r.Reason, "the BOM is gone: the header is read, and {} lacks its fields")

	text, err = Decode([]byte("\xef\xbb\xbf\xef\xbb\xbf---\n{}\n---\n"))
	require.NoError(t, err)
	_, err = Read(text)
	require.ErrorAs(t, err, &r)
	assert.Equal(t, MalformedHeader, r.Reason)
}

func TestRead_modelOfTheMinimalFixture(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join(repoRoot(t), "lumnik-hub/src/test/resources/proposal/fixtures/valid/minimal.md"))
	require.NoError(t, err)
	d, err := Read(string(raw))
	require.NoError(t, err)
	assert.Equal(t, "2026.10.0", d.Catalogue)
	assert.Equal(t, "2026-10-03", d.Created)
	require.Len(t, d.Leads, 1)
	lead := d.Leads[0]
	assert.Equal(t, "stalled-documents@1", lead.Rule)
	assert.Equal(t, "Workflow", lead.ProposesKind)
	assert.Equal(t, []string{"spec.on.table", "spec.on.column", "spec.on.key", "spec.dormancy.after"}, lead.Uses)
	require.Len(t, lead.Conditions, 5)
	assert.Equal(t, "C1", lead.Conditions[0].ID)
	assert.Nil(t, lead.Conditions[0].Evidence)
	assert.NotEmpty(t, lead.Conditions[3].Note)
}

// The model holds what draft.go reads and nothing more: a field only a test reads is a promise nobody
// keeps. The inventory is anchored on the production source, so a dead field cannot come back unnoticed.
// It is by selector name, which cannot tell two types' same-named fields apart; that is enough for
// fields as specific as these.
func TestModelHoldsOnlyWhatDraftReads(t *testing.T) {
	src, err := parser.ParseFile(token.NewFileSet(), "draft.go", nil, 0)
	require.NoError(t, err)
	read := readSelectors(src)
	assert.Empty(t, unreadFields(read, Dossier{}, Lead{}, Condition{}, Evidence{}),
		"fields no production code reads: remove them, or read them")
}

// readSelectors is the set of field names src selects off a value. It tells a model's selector from an
// import's qualifier (strings.Join) by syntax alone: a name an import binds is a package, not a value.
// A local that shadows an import is not told apart; draft.go has none, and go/types would be the cost.
func readSelectors(src *ast.File) map[string]bool {
	pkgs := map[string]bool{}
	for _, imp := range src.Imports {
		path, _ := strconv.Unquote(imp.Path.Value)
		name := path[strings.LastIndex(path, "/")+1:]
		if imp.Name != nil {
			name = imp.Name.Name
		}
		pkgs[name] = true // "." and "_" bind no qualifier, and no selector is written with them
	}
	read := map[string]bool{}
	ast.Inspect(src, func(n ast.Node) bool {
		if sel, ok := n.(*ast.SelectorExpr); ok {
			if x, isIdent := sel.X.(*ast.Ident); !isIdent || !pkgs[x.Name] {
				read[sel.Sel.Name] = true
			}
		}
		return true
	})
	return read
}

// unreadFields names the exported fields of the given structs that read does not contain.
func unreadFields(read map[string]bool, models ...any) []string {
	var dead []string
	for _, m := range models {
		typ := reflect.TypeOf(m)
		for i := 0; i < typ.NumField(); i++ {
			if f := typ.Field(i); f.IsExported() && !read[f.Name] {
				dead = append(dead, typ.Name()+"."+f.Name)
			}
		}
	}
	return dead
}

func TestUnreadFields_ignoresUnexportedFields(t *testing.T) {
	type model struct{ Used, Dead, cache string }
	assert.Equal(t, []string{"model.Dead"}, unreadFields(map[string]bool{"Used": true}, model{}))
}

func TestReadSelectors_ignoresImportQualifiers(t *testing.T) {
	src, err := parser.ParseFile(token.NewFileSet(), "x.go", `package x
import (
	"strings"
	s "sort"
	. "errors"
	_ "fmt"
)
func f(m Model) {
	_ = m.Used
	_ = m.inner.Nested
	_ = strings.Dead
	_ = s.AliasDead
}`, 0)
	require.NoError(t, err)
	assert.Equal(t, map[string]bool{"Used": true, "inner": true, "Nested": true}, readSelectors(src))
}
