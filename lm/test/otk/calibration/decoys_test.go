package calibration

// decoys are real detail strings, taken verbatim in shape from the OTHER checks'
// own message formats (checks/reconcile.go, atomicity.go, auth.go, divergence.go,
// schema.go, text.go) with plausible numbers filled in.
//
// They exist so specificity can be PROVED rather than detected: every declaration's
// pattern must match its own sample and none of these. `.*` matches all of them and
// is refused by construction, with no denylist of "obviously universal" expressions
// to keep current — a regexp can be universal in unbounded ways, and enumerating
// them is the same leaky guard in a new place.
//
// When a new check ships a new message format, add it here. A decoy list anchored on
// the wording production code actually emits is the only version of this guard that
// does not go stale in silence.
var decoys = []string{
	"ingestion did not conserve rows: in=4, out=3, skipped=0, dlq=0 (out+skipped=3)",
	"the DLQ holds rows the skip counter never counted: dlq=2 > skipped=0",
	"API/DB divergence on apply: API reported 1 new connector(s), DB gained 0 row(s)",
	"connector.connector row count changed during apply: 3 -> 4",
	"apply created 2 connectors from a single manifest",
	"apply rejected (exit 1) but leaked 1 connector(s)",
	"auth bypass landed (expired-token): expected 401, hub returned 200",
	"cross-tenant probe (tenant-2): expected 200 (empty), hub returned 200",
	"refused a legitimate request (valid-token): expected 2xx, hub returned 403",
	"raw control/bidi rune U+202E reflected into lm output",
}
