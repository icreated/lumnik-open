package tui

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/icreated/lumnik-open/lm/internal/client"
	"github.com/icreated/lumnik-open/lm/internal/tui/resources"
	"github.com/rivo/tview"
)

// onboarding connects a source from inside lm ui (#1117) — what lm source jdbc add --interactive
// and lm csv ingest do from a shell. Its seams (ask, pick, say, fail, done) are the App's prompt,
// in-app picker, toast, error and page switch; a test answers them directly. Hub calls run off the
// event loop through app (nil in a test: inline).
type onboarding struct {
	app    *tview.Application
	client *client.Client
	ask    resources.Prompt
	pick   func(tables []client.JdbcTable, done func([]client.JdbcTable))
	say    func(string)
	fail   func(string)
	done   func(page string)
}

// step is one question of a guided setup.
type step struct {
	label  string
	masked bool
}

// askAll asks steps in order and hands done every answer — trimmed, except a masked one.
func (o onboarding) askAll(steps []step, done func(answers []string)) {
	var answers []string
	var next func(i int)
	next = func(i int) {
		if i == len(steps) {
			done(answers)
			return
		}
		o.ask(steps[i].label, steps[i].masked, func(a string) {
			if !steps[i].masked {
				a = strings.TrimSpace(a)
			}
			answers = append(answers, a)
			next(i + 1)
		})
	}
	next(0)
}

// jdbcSource is the connection a JDBC setup asks once and every call reuses.
type jdbcSource struct{ name, url, user, pwd, schema string }

// jdbc asks the connection (the password masked, never shown), tests it, discovers the schema,
// offers the tables and adds one connector per table picked.
func (o onboarding) jdbc() {
	o.askAll([]step{
		{"Source name: ", false}, {"JDBC URL: ", false}, {"User: ", false},
		{"Password (hidden): ", true}, {"Schema (Enter: public): ", false},
	}, func(a []string) {
		src := jdbcSource{name: a[0], url: a[1], user: a[2], pwd: a[3], schema: a[4]}
		if src.schema == "" {
			src.schema = "public"
		}
		o.jdbcConnect(src)
	})
}

func (o onboarding) jdbcConnect(src jdbcSource) {
	o.say("connecting to " + src.name + "…") // never the URL: it can carry credentials of its own
	resources.Background(o.app, func() func() {
		test, err := o.client.JdbcTest(client.JdbcTestRequest{URL: src.url, User: src.user, Password: src.pwd})
		if err != nil {
			return func() { o.fail("test: " + err.Error()) }
		}
		disc, err := o.client.JdbcDiscover(client.JdbcDiscoverRequest{URL: src.url, User: src.user, Password: src.pwd, Schema: src.schema})
		return func() {
			if err != nil {
				o.fail("discover: " + err.Error())
				return
			}
			o.say(fmt.Sprintf("connected: %s %s (%d ms) — %d tables in %s", test.Dialect, test.Version, test.LatencyMs, len(disc.Tables), src.schema))
			o.pick(disc.Tables, func(picked []client.JdbcTable) {
				if len(picked) == 0 {
					o.say("no table picked — nothing added")
					return
				}
				o.jdbcAdd(src, picked)
			})
		}
	})
}

func (o onboarding) jdbcAdd(src jdbcSource, picked []client.JdbcTable) {
	tables := make([]client.JdbcAddTable, len(picked))
	for i, t := range picked {
		wm := ""
		if t.SuggestedWatermark != nil {
			wm = *t.SuggestedWatermark
		}
		tables[i] = client.JdbcAddTable{Table: t.Name, Watermark: wm} // mode is the hub's call
	}
	resources.Background(o.app, func() func() {
		resp, err := o.client.JdbcAdd(client.JdbcAddRequest{
			SourceName: src.name, URL: src.url, User: src.user, Password: src.pwd, Schema: src.schema, Tables: tables})
		return func() {
			if err != nil {
				o.fail("add: " + err.Error())
				return
			}
			names := make([]string, len(resp.Created))
			for i, e := range resp.Created {
				names[i] = e.ConnectorName
			}
			msg := fmt.Sprintf("✓ created %d connector(s): %s", len(resp.Created), strings.Join(names, ", "))
			if len(resp.Skipped) > 0 {
				msg += fmt.Sprintf(" · %d already existed", len(resp.Skipped))
			}
			o.say(msg)
			o.done("sources")
		}
	})
}

// csv ingests one local CSV file once: the hub infers the schema, creates (or reuses) a csv-file
// connector and lands the rows.
func (o onboarding) csv() {
	o.askAll([]step{
		{"CSV file (local path): ", false},
		{"Connector name (Enter: from the file name): ", false},
		{"Scope (Enter: the hub's default): ", false},
	}, func(a []string) {
		path, name, scope := a[0], a[1], a[2]
		o.say("ingesting " + path + "…")
		resources.Background(o.app, func() func() {
			f, err := os.Open(path)
			if err != nil {
				return func() { o.fail("open: " + err.Error()) }
			}
			defer f.Close()
			res, err := o.client.CsvIngest(f, path, client.CsvIngestOpts{Name: name, Scope: scope})
			return func() {
				if err != nil {
					o.fail("ingest: " + err.Error())
					return
				}
				o.say(fmt.Sprintf("✓ ingested %s into %s — %d rows written", path, res.TableName, res.RowsWritten))
				o.done("tables")
			}
		})
	})
}

// onboard is :new's w on a kind with a guided path; it reports whether the kind has one.
func (a *App) onboard(kind string) bool {
	o := onboarding{
		app:    a.tv,
		client: a.client,
		ask:    a.prompt,
		pick:   a.pickTables,
		say:    func(m string) { a.footer.SetToast(a.tv, m, 5*time.Second) },
		fail:   a.showError,
		done:   a.switchTo,
	}
	switch kind {
	case "jdbc-generic":
		o.jdbc()
	case "csv-file":
		o.csv()
	default:
		return false
	}
	return true
}

// pickTables mounts the table picker over lm ui; Esc picks nothing.
func (a *App) pickTables(tables []client.JdbcTable, done func([]client.JdbcTable)) {
	if a.retired.Load() {
		return // a discovery answered by the hub this session left
	}
	list := newTablePicker(tables, " pick the tables — Space: toggle · Enter: add · Esc: cancel ",
		func(picked []client.JdbcTable, confirmed bool) {
			a.restoreRoot()
			if !confirmed {
				picked = nil
			}
			done(picked)
		})
	a.setRoot(list)
	a.tv.SetFocus(list)
}
