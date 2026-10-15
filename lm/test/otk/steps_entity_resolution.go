//go:build otk

package otk

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"

	"github.com/cucumber/godog"
	"github.com/google/uuid"
	"github.com/icreated/lumnik-open/lm/test/otk/generator"
	"github.com/icreated/lumnik-open/lm/test/otk/oracle"
)

// The entity-resolution lane (#643, part of #613's arc): three properties #622's REST doors
// must hold that a single request/response check cannot express — each needs a short scripted
// sequence (seed rows, resolve, assert, resolve again) rather than one call. See
// generator/catalog_entity_resolution.go for the three named cases this lane drives.
const (
	stepResFixtureSeeded  = `^the resolution fixture "([^"]*)" is seeded(?: \[seed=(-?\d+)\])?$`
	stepResRunPass        = `^I run a resolution pass on "([^"]*)"$`
	stepResAssertDistinct = `^I assert distinct on the fixture's linked pair$`
	stepResCorrupt        = `^I corrupt the fixture's first row to collide with its second row's resolved key$`
	stepResAmbiguous      = `^the fixture's outcome is ambiguous, never merged$`
	stepResSurvives       = `^the distinct assertion still holds after the replay$`
	stepResSwapRefused    = `^the prior resolution is refused and recorded, not silently swapped$`
)

func init() {
	stepPatterns = append(stepPatterns,
		stepResFixtureSeeded, stepResRunPass, stepResAssertDistinct, stepResCorrupt,
		stepResAmbiguous, stepResSurvives, stepResSwapRefused)
	laneRegistrars = append(laneRegistrars, func(ctx *godog.ScenarioContext, st *scenarioState) {
		ctx.Step(stepResFixtureSeeded, func(name, seedStr string) error { return resFixtureSeeded(st, name, seedStr) })
		ctx.Step(stepResRunPass, func(name string) error { return resRunPass(st, name) })
		ctx.Step(stepResAssertDistinct, func() error { return resAssertDistinct(st) })
		ctx.Step(stepResCorrupt, func() error { return resCorrupt(st) })
		ctx.Step(stepResAmbiguous, func() error { return resThenAmbiguous(st) })
		ctx.Step(stepResSurvives, func() error { return resThenSurvives(st) })
		ctx.Step(stepResSwapRefused, func() error { return resThenSwapRefused(st) })
	})
}

const resEntityName = "otk_res_customer"
const resTable = "t_otk_res_source"

// resTableB is the second physical source (see seed_entity_resolution.go's resTableDDL comment):
// case 2's pair must live in two DIFFERENT sources for their natural auto-fusion to be possible
// at all — core.entity_identity's uq_entity_identity_cardinality constraint forbids two rows of
// the SAME source ever claiming the same entity_id.
const resTableB = "t_otk_res_source_b"

// resFixtureState carries what one resolution-lane scenario needs across its Given/When/Then
// steps — the row(s) under test, and (case-dependent) the identity_ref/entity_id values the
// fixture discovered while seeding, which the Then step compares against.
type resFixtureState struct {
	caseName    string
	seed        int64  // for findings — this lane's own Given step never touches st.scen
	targetID    string // the source_id whose outcome the Then step checks
	otherID     string // case 2/3: the pair's other source_id
	priorEntity string // case 3 only: the entity_id the target row held before the swap attempt
	leftRef     string // case 2 only: identity_ref asserted distinct (targetID)
	rightRef    string // case 2 only: identity_ref asserted distinct (otherID)
}

// ---- HTTP helpers (mirror entityApplyHTTP/entityReadHTTP in steps_entity.go) ----

func resDoJSON(method, path, token string, body []byte) (int, string, error) {
	req, err := http.NewRequest(method, entityBaseURL()+path, bytes.NewReader(body))
	if err != nil {
		return 0, "", err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	req.Header.Set("X-Tenant-ID", otkTenant)
	req.Header.Set("X-User-ID", otkTenant)
	cl := entityHTTPClient()
	resp, err := cl.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b), nil
}

func entityResolveHTTP(name, token string) (int, string, error) {
	return resDoJSON("POST", "/api/entities/"+name+"/resolve", token, []byte{})
}

func entityAmbiguousHTTP(name, token string) (int, string, error) {
	return resDoJSON("GET", "/api/entities/"+name+"/resolution/ambiguous?limit=1000", token, nil)
}

func entityHistoryHTTP(name, source, sourceID, token string) (int, string, error) {
	return resDoJSON("GET", "/api/entities/"+name+"/resolution/identity/"+source+"/"+sourceID, token, nil)
}

func entityAssertHTTP(name, verdict, leftRef, rightRef, reason, token string) (int, string, error) {
	body, err := json.Marshal(map[string]string{"leftRef": leftRef, "rightRef": rightRef, "reason": reason})
	if err != nil {
		return 0, "", err
	}
	return resDoJSON("POST", "/api/entities/"+name+"/assertions/"+verdict, token, body)
}

// ---- DB-truth helpers (mirror hubsnap.go's tri-state posture; this lane requires OTK_DB_DSN
// unconditionally — it has no dormant path of its own, since seed_entity_resolution.go's own
// seeder already fails fast without it) ----

func resAdminDSN() string { return envOr("OTK_DB_ADMIN_DSN", os.Getenv("OTK_DB_DSN")) }

// resPsql runs one statement via psql -At (single scalar/row-oriented reads) against the admin
// DSN — same flag ordering and -w posture as hubsnap.go, for the same reasons.
func resPsql(sql string) (string, error) {
	psql, err := exec.LookPath("psql")
	if err != nil {
		return "", fmt.Errorf("entity-resolution lane: psql not on PATH")
	}
	out, err := exec.Command(psql, "-X", "-At", "-w", "-d", resAdminDSN(), "-c", sql).Output()
	if err != nil {
		return "", psqlErr("entity-resolution DB-truth", err)
	}
	return strings.TrimSpace(string(out)), nil
}

// resUpsertRow (re)sets one t_otk_res_source row to exactly the given email — ON CONFLICT DO
// UPDATE, not DO NOTHING: a prior run of case 3's corruption step must not leak into the next
// run's starting state, so every Given step resets its rows to their canonical content.
func resUpsertRow(table, sourceID, email string) error {
	// The physical column is src_id, not source_id — see seedEntityResolutionManifest's comment
	// (a source_id column would collide, unqualified, with core.entity_identity's own column of
	// that name inside EntityViewCompiler's resolved-view join).
	sql := fmt.Sprintf(
		"INSERT INTO connector.%[1]s (tenant_id, src_id, email) VALUES (1, %[2]s, %[3]s) "+
			"ON CONFLICT DO NOTHING; "+
			"UPDATE connector.%[1]s SET email = %[3]s WHERE tenant_id = 1 AND src_id = %[2]s",
		table, sqlQuote(sourceID), sqlQuote(email))
	_, err := resPsql(sql)
	return err
}

// sqlQuote is a minimal literal-string quoter for the fixed, harness-controlled source ids and
// emails this file builds SQL from (never user/generator input) — doubling embedded quotes is
// sufficient because these values never contain anything else.
func sqlQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", "''") + "'"
}

// resIdentityRef reads a row's identity_ref via DB truth — needed for case 2's assertion door,
// which addresses identity_ref, not source/sourceId; #622's REST surface has no door that hands
// one back for a RESOLVED (non-ambiguous) row, only /resolution/ambiguous does.
func resIdentityRef(table, sourceID string) (string, error) {
	sql := fmt.Sprintf(
		"SET app.current_tenant = '1'; SELECT identity_ref FROM core.entity_identity "+
			"WHERE tenant_id = 1 AND entity_type = %s AND source = %s AND source_id = %s",
		sqlQuote(resEntityName), sqlQuote(table), sqlQuote(sourceID))
	out, err := resPsql(sql)
	if err != nil {
		return "", err
	}
	ref := lastLine(out)
	if ref == "" {
		return "", fmt.Errorf("entity-resolution lane: no identity_ref yet for source_id %q — resolve it first", sourceID)
	}
	return ref, nil
}

// resEntityID reads a row's current entity_id via DB truth (empty string when null/unresolved).
func resEntityID(table, sourceID string) (string, error) {
	sql := fmt.Sprintf(
		"SET app.current_tenant = '1'; SELECT coalesce(entity_id::text, '') FROM core.entity_identity "+
			"WHERE tenant_id = 1 AND entity_type = %s AND source = %s AND source_id = %s",
		sqlQuote(resEntityName), sqlQuote(table), sqlQuote(sourceID))
	out, err := resPsql(sql)
	if err != nil {
		return "", err
	}
	return lastLine(out), nil
}

// resSeedGhost copies sourceOfTruth's OWN (rule_version, key_fingerprint) for ruleID onto a
// synthetic "already resolved" identity naming a brand-new random entity — a legacy/pre-existing
// record this harness cannot otherwise construct, since it has no way to compute the real HMAC
// fingerprint itself. Copying real bytes (rather than forging them) is what keeps this a DB-truth
// fixture rather than a guess: whatever EntityFingerprintService actually produced for
// sourceOfTruth is exactly what the ghost now shares, byte for byte. ON CONFLICT DO UPDATE so a
// rerun re-derives it fresh from the current real fingerprint rather than trusting a stale copy.
//
// sourceOfTruth must be a row this harness deletes (resDeleteRow) once its fingerprint is copied,
// never one that stays real and resolved: candidateEntityIds() does not exclude the row being
// re-resolved from its own candidate query, so a REAL row that keeps sharing a ghost's exact key
// would see itself + the ghost as two distinct entities on every later full-entity-type replay
// (every /resolve call, from ANY case) and flip itself to ambiguous — which then orphans the
// ghost as some OTHER row's sole remaining candidate, silently merging it (or, worse, violating
// uq_entity_identity_cardinality when that other row shares the ghost's `source`). Two ghosts
// sharing ONE throwaway row's fingerprint, with the throwaway itself deleted right after, means no
// real row ever again shares that key except the row under test — which is SUPPOSED to end up
// ambiguous/excluded, making it self-stabilizing rather than cascading.
func resSeedGhost(ghostSourceID, sourceOfTruth, ruleID string) error {
	sql := fmt.Sprintf(
		"SET app.current_tenant = '1'; "+
			"SELECT rule_version, key_fingerprint FROM core.entity_identity_key "+
			"WHERE tenant_id = 1 AND entity_type = %s AND source = %s AND source_id = %s AND rule_declared_id = %s",
		sqlQuote(resEntityName), sqlQuote(resTable), sqlQuote(sourceOfTruth), sqlQuote(ruleID))
	out, err := resPsql(sql)
	if err != nil {
		return err
	}
	line := lastLine(out)
	parts := strings.Split(line, "|")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return fmt.Errorf("entity-resolution lane: %q has no %q key yet to copy (resolve it first): %q",
			sourceOfTruth, ruleID, out)
	}
	ruleVersion, fingerprintHex := parts[0], strings.TrimPrefix(parts[1], "\\x")
	ghostEntity := uuid.New().String()

	insertSQL := fmt.Sprintf(
		"INSERT INTO core.entity_identity (tenant_id, entity_type, source, source_id, entity_id, state, decided_by) "+
			"VALUES (1, %s, %s, %s, %s, 'resolved', 'automatic') "+
			"ON CONFLICT (tenant_id, entity_type, source, source_id) DO UPDATE SET "+
			"entity_id = EXCLUDED.entity_id, state = EXCLUDED.state, decided_by = EXCLUDED.decided_by; "+
			"INSERT INTO core.entity_identity_key (tenant_id, entity_type, source, source_id, rule_declared_id, rule_version, key_fingerprint) "+
			"VALUES (1, %s, %s, %s, %s, %s, decode(%s, 'hex')) "+
			"ON CONFLICT (tenant_id, entity_type, source, source_id, rule_declared_id) DO UPDATE SET "+
			"rule_version = EXCLUDED.rule_version, key_fingerprint = EXCLUDED.key_fingerprint",
		sqlQuote(resEntityName), sqlQuote(resTable), sqlQuote(ghostSourceID), sqlQuote(ghostEntity),
		sqlQuote(resEntityName), sqlQuote(resTable), sqlQuote(ghostSourceID), sqlQuote(ruleID),
		sqlQuote(ruleVersion), sqlQuote(fingerprintHex))
	_, err = resPsql(insertSQL)
	return err
}

func lastLine(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return strings.TrimSpace(lines[len(lines)-1])
}

// resResetIdentity erases one identity's ENTIRE resolution footprint — assertion, evidence, key
// and crosswalk rows, in FK-safe order — so a Given step can restore a genuinely clean baseline no
// matter what a PREVIOUS run against the SAME persistent DB left behind (icreated's P2 review on
// PR #664: the committed corpus reuses the exact same seed -> source_id mapping on every run —
// weekly Gold, a local rerun, anything reusing the tenant-1 DB — so this is not a hypothetical).
//
// Resetting only the physical row's content (resUpsertRow's email reset) is not enough:
// CascadeResolver's "never silently recycle a resolved link" guard has no expiry by design — once
// a row is ever refused a swap (or simply ends up ambiguous), core.entity_resolution_evidence's
// append-only memory of "this row used to resolve to X" persists and keeps refusing every LATER
// resolve too, even a genuinely fresh one on the next run. lastResolvedEntityId reads that memory
// by (tenant, entity_type, source, source_id) — independent of identity_ref — so evidence rows
// must be deleted explicitly; entity_resolution_evidence's FK is ON DELETE SET NULL, which nulls
// the reference but leaves the row (and its bias) in place. entity_assertion's FK is ON DELETE
// RESTRICT, so it must go first or the final entity_identity delete fails outright.
// entity_identity_key cascades automatically once entity_identity is gone.
func resResetIdentity(table, sourceID string) error {
	identityFilter := fmt.Sprintf("tenant_id = 1 AND entity_type = %s AND source = %s AND source_id = %s",
		sqlQuote(resEntityName), sqlQuote(table), sqlQuote(sourceID))
	sql := fmt.Sprintf(
		"DELETE FROM core.entity_assertion WHERE tenant_id = 1 AND entity_type = %s AND ("+
			"left_identity_ref = (SELECT identity_ref FROM core.entity_identity WHERE %s) "+
			"OR right_identity_ref = (SELECT identity_ref FROM core.entity_identity WHERE %s)); "+
			"DELETE FROM core.entity_resolution_evidence WHERE %s; "+
			"DELETE FROM core.entity_identity WHERE %s",
		sqlQuote(resEntityName), identityFilter, identityFilter, identityFilter, identityFilter)
	_, err := resPsql(sql)
	return err
}

// resDeleteRow removes a throwaway source row completely — its whole resolution footprint
// (resResetIdentity) plus the physical row itself — see resSeedGhost's doc comment for why a
// fingerprint-copy source must not stay real and resolved.
func resDeleteRow(table, sourceID string) error {
	if err := resResetIdentity(table, sourceID); err != nil {
		return err
	}
	sql := fmt.Sprintf("DELETE FROM connector.%s WHERE tenant_id = 1 AND src_id = %s", table, sqlQuote(sourceID))
	_, err := resPsql(sql)
	return err
}

// ---- Given ----

func resFixtureSeeded(st *scenarioState, name, seedStr string) error {
	if entityBaseURL() == "" {
		return fmt.Errorf("entity-resolution lane: LUMNIK_URL is not set")
	}
	if os.Getenv("OTK_DB_DSN") == "" {
		recordDormant(generator.EntityResolutionCaseID(name), "entity-resolution lane: set OTK_DB_DSN + psql "+
			"(this lane has no read-only path; it drives DB-truth fixture setup and #622's REST doors)")
		st.resDormant = true
		return nil
	}
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("entity-resolution lane: mint integrator token: %w", err)
	}

	seed := parseSeed(seedStr)
	// Every source_id is seed-suffixed: /resolve replays the WHOLE entity type, so every
	// scenario's setup call also reprocesses every OTHER seed's rows still sitting in the shared
	// fixture tables. Fixed, reused ids would let one occurrence's outcome (an ambiguous or
	// swap-refused row) leak into the next occurrence of the SAME case as stale starting state —
	// worse, once a row is ever refused a swap, CascadeResolver's ledger-based guard means it can
	// never cleanly return to an ordinary resolved baseline again (by design: "never silently
	// recycle a resolved link" has no expiry), so reusing "c3-d" across seeds would permanently
	// wedge it ambiguous after its first corruption. Seed-suffixing gives every occurrence its own
	// rows nothing else ever touches.
	switch name {
	case "ambiguous-mailbox-never-merges":
		seedRow := fmt.Sprintf("c1-seed-%d", seed)
		ghostAlice := fmt.Sprintf("c1-alice-ghost-%d", seed)
		ghostBob := fmt.Sprintf("c1-bob-ghost-%d", seed)
		charlie := fmt.Sprintf("c1-charlie-%d", seed)
		email := fmt.Sprintf("sharedbox1-%d@otk.test", seed)
		// seedRow exists only to make EntityFingerprintService compute a REAL "email" fingerprint
		// this harness can copy (it has no way to derive the HMAC itself) — deleted immediately
		// after, per resSeedGhost's doc comment, so it never becomes a second real row destabilized
		// by sharing a ghost's key. Both "already-known people" charlie is compared against are
		// synthetic ghosts, not one real + one synthetic — otherwise the real one would flip to
		// ambiguous on the very next full-entity-type replay (any case's /resolve call), orphaning
		// the ghost it shares a key with.
		//
		// resResetIdentity before every seed (here and for charlie below): a rerun against the same
		// persistent DB must never inherit a prior run's ambiguous/refused-swap history — see
		// resResetIdentity's doc comment.
		if err := resResetIdentity(resTable, seedRow); err != nil {
			return err
		}
		if err := resUpsertRow(resTable, seedRow, email); err != nil {
			return err
		}
		if status, body, err := entityResolveHTTP(resEntityName, token); err != nil {
			return err
		} else if status != 200 {
			return fmt.Errorf("entity-resolution lane: fixture setup resolve returned %d: %s", status, body)
		}
		if err := resSeedGhost(ghostAlice, seedRow, "email"); err != nil {
			return err
		}
		if err := resSeedGhost(ghostBob, seedRow, "email"); err != nil {
			return err
		}
		if err := resDeleteRow(resTable, seedRow); err != nil {
			return err
		}
		// charlie lives in the OTHER source: an entity that already holds a row of a source is never
		// chosen for another row of that same source (#889), so a charlie beside the ghosts would
		// mint its own entity — correctly never merged, but not ambiguous either. Ambiguity needs a
		// row of a different source meeting both candidates.
		if err := resResetIdentity(resTableB, charlie); err != nil {
			return err
		}
		if err := resUpsertRow(resTableB, charlie, email); err != nil {
			return err
		}
		st.res = resFixtureState{caseName: name, seed: seed, targetID: charlie}

	case "distinct-assertion-survives-replay":
		a := fmt.Sprintf("c2-a-%d", seed)
		b := fmt.Sprintf("c2-b-%d", seed)
		email := fmt.Sprintf("sharedbox2-%d@otk.test", seed)
		// Two DIFFERENT sources — see resTableB's doc comment: the same source can never
		// auto-fuse two of its own rows into one entity (uq_entity_identity_cardinality).
		// resResetIdentity before every seed — same rerun-safety reasoning as case 1, see its
		// doc comment.
		if err := resResetIdentity(resTable, a); err != nil {
			return err
		}
		if err := resResetIdentity(resTableB, b); err != nil {
			return err
		}
		if err := resUpsertRow(resTable, a, email); err != nil {
			return err
		}
		if err := resUpsertRow(resTableB, b, email); err != nil {
			return err
		}
		if status, body, err := entityResolveHTTP(resEntityName, token); err != nil {
			return err
		} else if status != 200 {
			return fmt.Errorf("entity-resolution lane: fixture setup resolve returned %d: %s", status, body)
		}
		leftRef, err := resIdentityRef(resTable, a)
		if err != nil {
			return err
		}
		rightRef, err := resIdentityRef(resTableB, b)
		if err != nil {
			return err
		}
		// Which of the two ends up ambiguous after the assertion depends on the
		// resolution pass's own row-processing order (whichever it revisits FIRST still
		// sees its partner as "resolved" and gets vetoed into ambiguity; the second sees
		// the first already flipped and simply keeps its prior state) — not on which
		// source_id is which. resThenSurvives discovers the actual target dynamically
		// rather than trusting an assumed order here.
		st.res = resFixtureState{caseName: name, seed: seed, targetID: a, otherID: b, leftRef: leftRef, rightRef: rightRef}

	case "stale-resolution-swap-refused":
		d := fmt.Sprintf("c3-d-%d", seed)
		e := fmt.Sprintf("c3-e-%d", seed)
		emailD := fmt.Sprintf("distinct-d-%d@otk.test", seed)
		emailE := fmt.Sprintf("distinct-e-%d@otk.test", seed)
		// resResetIdentity before every seed — same rerun-safety reasoning as case 1, see its doc
		// comment. This is the case icreated's P2 review on PR #664 caught: d is DELIBERATELY left
		// refused/ambiguous by this scenario's own Then step, so without this reset a rerun of the
		// SAME committed seed would find d already ambiguous before ever reaching the swap attempt.
		if err := resResetIdentity(resTable, d); err != nil {
			return err
		}
		if err := resResetIdentity(resTable, e); err != nil {
			return err
		}
		if err := resUpsertRow(resTable, d, emailD); err != nil {
			return err
		}
		if err := resUpsertRow(resTable, e, emailE); err != nil {
			return err
		}
		if status, body, err := entityResolveHTTP(resEntityName, token); err != nil {
			return err
		} else if status != 200 {
			return fmt.Errorf("entity-resolution lane: fixture setup resolve returned %d: %s", status, body)
		}
		priorEntity, err := resEntityID(resTable, d)
		if err != nil {
			return err
		}
		if priorEntity == "" {
			return fmt.Errorf("entity-resolution lane: fixture setup did not resolve %q before the swap attempt", d)
		}
		st.res = resFixtureState{caseName: name, seed: seed, targetID: d, otherID: e, priorEntity: priorEntity}

	default:
		return fmt.Errorf("entity-resolution lane: unknown fixture case %q", name)
	}
	return nil
}

// ---- When ----

func resRunPass(st *scenarioState, name string) error {
	if st.resDormant {
		return nil
	}
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("entity-resolution lane: mint integrator token: %w", err)
	}
	status, body, err := entityResolveHTTP(name, token)
	if err != nil {
		return fmt.Errorf("entity-resolution lane: POST /api/entities/%s/resolve: %w", name, err)
	}
	if status != 200 {
		return fmt.Errorf("entity-resolution lane: resolve pass returned %d (want 200): %s", status, body)
	}
	return nil
}

func resAssertDistinct(st *scenarioState) error {
	if st.resDormant {
		return nil
	}
	token, err := currentToken()
	if err != nil {
		return fmt.Errorf("entity-resolution lane: mint integrator token: %w", err)
	}
	status, body, err := entityAssertHTTP(resEntityName, "distinct", st.res.leftRef, st.res.rightRef,
		"OTK fixture: shared mailbox, asserted distinct people", token)
	if err != nil {
		return fmt.Errorf("entity-resolution lane: POST .../assertions/distinct: %w", err)
	}
	if status != 204 {
		return fmt.Errorf("entity-resolution lane: assert distinct returned %d (want 204): %s", status, body)
	}
	return nil
}

func resCorrupt(st *scenarioState) error {
	if st.resDormant {
		return nil
	}
	// The "rule-version change that would move an existing canonical id" the issue describes —
	// a source correction is the observable-equivalent trigger for the SAME guard (CascadeResolver's
	// "never silently recycle a resolved link"): either way, re-evaluation now names a different,
	// already-resolved entity than the one this row currently holds.
	emailE := fmt.Sprintf("distinct-e-%d@otk.test", st.res.seed)
	return resUpsertRow(resTable, st.res.targetID, emailE)
}

// ---- Then ----

// resFinding appends a Critical/Security finding — this lane's invariant violations are all
// "an identity ended up somewhere the resolver's own contract forbids", the same severity/family
// entityNoRows already uses for the analogous RLS-isolation breach.
func resFinding(st *scenarioState, detail string) {
	st.findings = append(st.findings, oracle.Finding{
		Severity: oracle.Critical, Family: oracle.Security,
		CaseID: generator.EntityResolutionCaseID(st.res.caseName), Seed: st.res.seed,
		Detail: detail,
	})
}

// resAmbiguousEntry finds targetID's own row in the ambiguous listing, or nil if absent.
type ambiguousRow struct {
	IdentityRef string   `json:"identityRef"`
	Source      string   `json:"source"`
	SourceID    string   `json:"sourceId"`
	Candidates  []string `json:"candidates"`
	DecidedBy   string   `json:"decidedBy"`
}

func resFetchAmbiguous(st *scenarioState) ([]ambiguousRow, error) {
	token, err := currentToken()
	if err != nil {
		return nil, err
	}
	status, body, err := entityAmbiguousHTTP(resEntityName, token)
	if err != nil {
		return nil, fmt.Errorf("entity-resolution lane: GET .../resolution/ambiguous: %w", err)
	}
	if status != 200 {
		return nil, fmt.Errorf("entity-resolution lane: GET .../resolution/ambiguous returned %d: %s", status, body)
	}
	var rows []ambiguousRow
	if err := json.Unmarshal([]byte(body), &rows); err != nil {
		return nil, fmt.Errorf("entity-resolution lane: could not parse ambiguous listing: %w", err)
	}
	return rows, nil
}

func findRow(rows []ambiguousRow, sourceID string) *ambiguousRow {
	for i := range rows {
		if rows[i].SourceID == sourceID {
			return &rows[i]
		}
	}
	return nil
}

type historyEvent struct {
	Outcome    string   `json:"outcome"`
	DecidedBy  string   `json:"decidedBy"`
	Candidates []string `json:"candidates"`
}

func resFetchHistory(table, sourceID string) ([]historyEvent, error) {
	token, err := currentToken()
	if err != nil {
		return nil, err
	}
	status, body, err := entityHistoryHTTP(resEntityName, table, sourceID, token)
	if err != nil {
		return nil, fmt.Errorf("entity-resolution lane: GET .../resolution/identity/.../%s: %w", sourceID, err)
	}
	if status != 200 {
		return nil, fmt.Errorf("entity-resolution lane: GET .../resolution/identity/.../%s returned %d: %s",
			sourceID, status, body)
	}
	var events []historyEvent
	if err := json.Unmarshal([]byte(body), &events); err != nil {
		return nil, fmt.Errorf("entity-resolution lane: could not parse identity history for %s: %w", sourceID, err)
	}
	return events, nil
}

func resThenAmbiguous(st *scenarioState) error {
	if st.resDormant {
		return nil
	}
	rows, err := resFetchAmbiguous(st)
	if err != nil {
		return err
	}
	row := findRow(rows, st.res.targetID)
	if row == nil {
		resFinding(st, fmt.Sprintf("expected %q to be ambiguous (never merged) but it is absent from "+
			"GET .../resolution/ambiguous — it was silently resolved to one of two pre-existing candidates", st.res.targetID))
		return nil
	}
	if len(row.Candidates) < 2 {
		resFinding(st, fmt.Sprintf("%q is listed ambiguous but carries %d candidate(s), want >= 2 "+
			"(candidates=%v)", st.res.targetID, len(row.Candidates), row.Candidates))
	}
	return nil
}

func resThenSurvives(st *scenarioState) error {
	if st.resDormant {
		return nil
	}
	// Which of the pair ends up ambiguous depends on the resolution pass's own row-processing
	// order (see resFixtureSeeded), not on source_id — discover it from DB truth rather than
	// assuming targetID is the one. Exactly one of the pair must be ambiguous: if it is neither,
	// the assertion was silently ignored (a re-merge); if it is both, the veto over-applied.
	rows, err := resFetchAmbiguous(st)
	if err != nil {
		return err
	}
	target, other := findRow(rows, st.res.targetID), findRow(rows, st.res.otherID)
	switch {
	case target == nil && other == nil:
		resFinding(st, fmt.Sprintf("neither %q nor %q is ambiguous after the distinct assertion and two "+
			"replays — the pair was silently re-merged", st.res.targetID, st.res.otherID))
		return nil
	case target != nil && other != nil:
		resFinding(st, fmt.Sprintf("both %q and %q are ambiguous after the distinct assertion — want exactly "+
			"one (the assertion should leave the other cleanly resolved)", st.res.targetID, st.res.otherID))
		return nil
	}
	ambiguousID, ambiguousTable := st.res.targetID, resTable
	if other != nil {
		ambiguousID, ambiguousTable = st.res.otherID, resTableB
	}
	// decidedBy="asserted" proves WHY it is still ambiguous after two replays — that the
	// human veto, not chance, is what is driving the outcome (design doc §7's promise that a
	// distinct assertion survives replay, not merely the one call that recorded it).
	events, err := resFetchHistory(ambiguousTable, ambiguousID)
	if err != nil {
		return err
	}
	if len(events) == 0 {
		resFinding(st, fmt.Sprintf("no resolution history recorded for %q after the assertion+replay", ambiguousID))
		return nil
	}
	latest := events[len(events)-1] // evidenceHistory orders oldest first
	if !strings.EqualFold(latest.DecidedBy, "asserted") {
		resFinding(st, fmt.Sprintf("%q's latest resolution event is decided_by=%q after two replays "+
			"following a distinct assertion — want \"asserted\" (the veto must still be what drives the outcome, "+
			"not have quietly reverted to automatic)", ambiguousID, latest.DecidedBy))
	}
	return nil
}

func resThenSwapRefused(st *scenarioState) error {
	if st.resDormant {
		return nil
	}
	if err := resThenAmbiguous(st); err != nil {
		return err
	}
	events, err := resFetchHistory(resTable, st.res.targetID)
	if err != nil {
		return err
	}
	if len(events) == 0 {
		resFinding(st, fmt.Sprintf("no resolution history recorded for %q after the swap attempt", st.res.targetID))
		return nil
	}
	latest := events[len(events)-1] // evidenceHistory orders oldest first
	found := false
	for _, c := range latest.Candidates {
		if strings.EqualFold(c, st.res.priorEntity) {
			found = true
		}
	}
	if !found {
		resFinding(st, fmt.Sprintf("%q's refused-swap evidence does not record its prior entity %q among "+
			"candidates=%v — the ledger must explain what it stopped naming, not just that it did",
			st.res.targetID, st.res.priorEntity, latest.Candidates))
	}
	// The other side of "never a merge": the partner row must stay resolved to its own entity,
	// untouched by the target row's refused swap attempt.
	otherEntity, err := resEntityID(resTable, st.res.otherID)
	if err != nil {
		return err
	}
	if otherEntity == "" {
		resFinding(st, fmt.Sprintf("%q lost its own resolution as a side effect of %q's refused swap attempt",
			st.res.otherID, st.res.targetID))
	}
	return nil
}
