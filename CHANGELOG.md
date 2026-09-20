# Changelog — lumnik (open edition)

The open edition is a projection of the lumnik monorepo, republished per release. Version numbers
are aligned with it from v1.4.0 on: open-edition `vX.Y.Z` is the projection of monorepo `vX.Y.Z`.
Releases 1.1–1.3 existed upstream and were never published here — absent, not withdrawn.

This file lists only what ships in this repository. Closed modules (the RAG store, the langchain4j
binding, the Kafka connector, SCIM) are not part of it, and changes to them do not appear here.

## v1.7.3 — 2026-09-20

A métier app no longer needs a hub rebuild. This release's headline is `kind:App`: declare a métier
app's title, scope, icon and theme as a manifest, apply it, and the app surface picks it up live.
Everything else here tightens doors that were already meant to refuse and, in a few places, quietly
didn't.

### Added
- **`kind:App` manifests.** `lm app apply -f app.yaml` declares a métier app at runtime — title,
  `spec.scope`, a curated icon, and an accent/background theme (checked for WCAG AA contrast,
  since the accent colour is the chat bubble's background, not just its text). `lm app rm` removes
  one, refusing rather than silently promoting a successor if it would leave the deployment with no
  default app. The app surface (`/app/?m=<tag>`) now resolves its identity from this live registry
  instead of a name baked into the build: an unrecognized or out-of-scope tag gets a clear refusal
  instead of silently opening the wrong app.
- **`kind:Workflow` and `kind:View` gain delete and enable/disable**, matching what `kind:Process`
  already had. `lm workflow rm|enable|disable` and `lm view rm`.
- **Webhook deliveries carry `X-Lumnik-Event-Id`.** If you subscribe to both a `decision.*` event
  and its later acknowledgement, this header — the same id on the initial delivery, every retry,
  and every replay — is what lets you join the two without inventing your own correlation key.
- **The hub reports its own edition, and `lm login` shows it** alongside the binary version already
  printed there.
- **Six `lm` detail commands honour `-o json/yaml`** where they used to silently print the table
  form regardless: `describe connector-type`, `dlq get`, `secret describe`, `rag status`, `mapping
  get`, `hook get`. `hook get` also now decodes the *right* response shape — it used to print a
  correct id and silently blank everything else.
- **`lm hook apply/delete/reload` refuse honestly** instead of a bare 404. Hooks are registered
  once, from classpath manifests, at startup; there is no live CRUD to route those commands to,
  and now they say so.

### Changed — read before upgrading
- **A connector manifest apply and a flat CRUD write can no longer race each other into handing a
  manifest to the wrong métier.** Four apply doors (View, Process, Workflow, Entity) used to check
  who owns a name and write it in two separate steps; a concurrent legitimate apply landing between
  them could move a manifest out of a scope its actual owner still holds. The connector apply door
  gets the same guarantee through a row lock, since it works differently under the hood. Nothing
  changes in a manifest you apply alone; this only closes a window that needed two integrators
  racing the same name.
- **A connector edit that omits `tags` no longer wipes its scope.** If you use the `get → edit →
  apply` round trip on a scoped connector and your edited body doesn't carry `tags`, the connector
  keeps its existing scope grant instead of silently becoming visible tenant-wide. (`tags` are also
  now carried by the flat create/update door at all — a short-lived gap this same release closed
  and then closed again.)
- **A malformed cron schedule on the flat connector door is refused at write time**, not discovered
  later as a connector that silently never runs. The manifest apply door already validated this;
  the flat door now shares the same check.
- **`spec.scope` is validated the same way on every manifest kind.** `kind:View`, `Process` and
  `Workflow` used to accept a scope tag no Keycloak role could ever carry — the manifest applied
  cleanly and then was invisible to every scope-bound user, with no way to fix it afterwards.
- **An Ask answer that has to give up now always tells you why**, whichever of the three ways it
  gave up: no schema, a rejected generated query, or zero matching rows. All three now carry the
  same door signpost when your scope declares a lifecycle or a fused entity that could have
  answered instead.
- **A column you renamed with `map-columns` stays where it was** in the row instead of jumping to
  the end.
- **The schema shown for a dynamic table follows the source's real column order** again in every
  case — a storage detail had been scrambling it in the schema card (and in the text an ask query
  is generated against) even though the table itself was already correct.
- **A second connector mapped onto a column an earlier one created no longer fights it.** If a
  later chunk would need a wider column type than the one already on disk, the writer now checks
  the real table before deciding, instead of trusting its own fresh guess and failing on the
  insert that should have widened it.
- **`GET /app` redirects to `/app/`** instead of a bare 404.

### Testing
- The acceptance harness's Gold badge (`make otk-gold`) now measures coverage of a fixed 136-case
  catalogue instead of a scenario count that measured nothing meaningful. A new calibration mode
  (`make otk-calibrate`) deliberately breaks the hub with a declared fault to prove the checks can
  still fire, not only that they stayed quiet.
- This release ships `lm` **1.3.2** binaries (six archives, `checksums.txt` alongside).

## v1.7.2 — 2026-09-15

A patch release about refusals that said nothing. A `400` from the API used to reach `lm` with no
body at all, so the sentence naming your mistake never left the server. A run whose row had been
deleted mid-flight ended without telling anyone. And this is the first release you can install
without a Go toolchain.

### Added
- **Pre-built `lm` binaries are attached to this release.** Five archives — macOS (Intel and Apple
  Silicon), Linux (x86-64 and arm64), Windows x86-64 — with a checksums file beside them. Take the
  one for your platform, unpack it, put `lm` on your `PATH`; the Go toolchain is now needed only if
  you would rather build. The archives carry **`lm`'s own version**, which advances on its own tags
  rather than the hub's — that is why `lm_1.3.1_…` hangs on a `v1.7.2` release. `lm version` prints
  that number for a downloaded binary and `dev` for one you built, which is the line to quote in a
  bug report.

### Changed — read before upgrading
- **A refused request now tells you why.** Eleven doors used to answer `400` with an empty body, so
  `lm` printed `API error 400 (no details from the server)` and the admin console sent you to the
  server log to find the sentence. The sentence now travels on the wire. Nothing you send changes;
  what comes back does. One gap is deliberately left named rather than claimed closed: a request
  body malformed enough that the JSON parser gives up still answers empty.
- **A manifest key written with nothing after it is treated as absent — except inside a map you
  wrote.** `rename: {id: null}` still refuses, because "rename id to nothing" is a typo and not an
  omission; a declaration left empty means you did not declare it. The two look identical in YAML
  and only the schema tells them apart, so the doors now ask for absence by name instead of
  guessing. If a manifest of yours relied on the older, inconsistent reading, `lm validate` will
  say so before an apply does.
- **Building from source now needs Go 1.26+** (the floor is `lm/go.mod`, which CI follows). This
  only concerns you if you build rather than download.

### Fixed
- **A run whose connector was deleted mid-flight no longer goes silent.** The run row is read once
  when the chunk loop starts, and deleting a connector removes its runs — so a run already under
  way could find its own row gone. It then either left the row stuck at `Running` until the next
  restart, or finished it `Failed` **without emitting the event**, which is what `lm run list`, the
  TUI and every webhook subscriber read. It is a refusal now, announced like any other ending.
- **A chunk whose refusal could not even be built escaped the same funnel** — one layer down, the
  same defect.

## v1.7.1 — 2026-09-14

A patch release about what a key written with nothing after it means. Three blocks and one path
answered that question four different ways, and one of the answers moved your processed files into a
directory literally named `null`. Also: a first boot against an empty database stops logging errors
it was already recovering from on its own.

### Changed — read before upgrading
- **`spec.target` is no longer required, and your manifests can lose the line.** Its only key —
  `indexes` — was already optional, so every csv and jdbc manifest carried a `target: {}` the runtime
  read nothing from. On `csv-file` the block is now optional: write it and it is still checked key by
  key. On `jdbc-generic` it is gone entirely — nothing on that kind ever read it, since a jdbc
  connector's indexes are declared per table on `source.tables[].indexes`, so `target.indexes` was
  accepted and then dropped without a word. **A manifest still carrying the line keeps applying**, and
  a jdbc one now comes back with a warning inviting you to delete it. The same is true of csv's
  `spec.parser`, whose keys all carry a default. The manifests on this site and the `lm new` templates
  are already a line shorter.
- **`after_process: move` without an `archive_path` is now refused.** Written empty — `archive_path:`
  with nothing after it — it used to validate green and then move every processed file into a directory
  called `null`. Both layers close: `lm validate` refuses the combination, and an apply never stores
  that string whatever `after_process` says. A manifest of yours with that shape will now be refused;
  it was already not doing what it said.
- **Deleting the `parser` block now gets you the auto-detection the page promises.** An apply starts
  from the connector's existing configuration, so removing the block used to leave the previous run's
  delimiter in place, in silence. The same was true of `indexes`. Note what this does not do: removing
  an `indexes` declaration does not drop an index that was already created.

### Fixed
- **A first boot against an empty database no longer logs `relation does not exist`.** Scheduled work
  starts on its own threads, independently of the migrations, so its first tick could reach a table
  Flyway had not created yet — on `up.sh` against fresh volumes, and on a first cloud start. The error
  was caught and the next tick recovered, so nothing was ever lost; the log simply said otherwise.
  Scheduled work now waits until every migration has run.

### Added
- **`lm login` shows which binary you are running.** The page where you first meet a binary you may not
  have built now carries its version, next to the product signature. A binary you built yourself says
  `dev` — which is the truth, and a signal of its own.

## v1.7.0 — 2026-09-13

Six days of running the product against real sources — a live JDBC database, and a REST / GraphQL /
SOAP service the reader can start — rather than asserting about it. Five migrations run at startup,
one of them **deletes rows** and one **revokes a grant**: read **Changed** before upgrading. The
chat surface, the model bindings and the Kafka connector are closed modules and their changes are
not listed here.

This release also adds [**Where lumnik stands**](/docs/where-lumnik-stands.md) — one page carrying
the whole boundary: what is deliberately absent, what is started and not finished, and the one
thing we do not know yet. lumnik is **beta**, and that page says exactly what the word covers.

### Changed — read before upgrading
- **A cursor left by a different configuration is discarded, and the table is read again from the
  start.** Switching an endpoint between `full` and `watermark`, or changing the cursor column or
  the primary key, used to resume from the old position without saying so — a table of 100 rows
  reported 0 of 100, then 50 of 100. The stale position is now refused with a warning in the log,
  and the next run re-reads the table in full. Expect that one re-ingestion after any such change.
  `lm endpoint reset-cursor` asks for it on purpose.
- **A bounded run can end `Partial`.** A run that drains to the head of the data it found reports
  `Partial` rather than `Success`. If you parse run status, handle the fifth value.
- **Five migrations**, applied at startup:
    - One **removes the per-table mapping rows** `lm source jdbc add` used to write. Nothing ever
      read them back, and they held a name that `connector delete` never released — so a table you
      deleted could never be added again. Deleting them fixes that; no configuration of yours moves.
    - Three make a **deleted name usable again** — secrets, endpoints and integrations. A soft
      delete left the name reserved: `lm secret list` no longer showed it, `lm secret rm` answered
      *not found* for it, and creating it again failed with an anonymous `500`. The name is free now.
    - One makes the **audit trail append-only for the request-path database role**. Nothing in the
      hub ever wrote to it from there, but the grant allowed it — and an auditor reads grants, not
      intentions. No action needed unless your own tooling writes to `core.audit_event` as
      `lumnik_app`; it will now be refused.
- **An unknown key in a manifest is named instead of ignored in silence.** `poll_timeout: 50` in a
  spec that has no such key used to apply with no warning, so an integrator read a value the
  runtime never used. Nothing changes about what is accepted: the manifest is still valid, still
  applies, and the key is still stored — it just no longer reads as if it did something.

### Security
- **A disabled account can be refused at once instead of at token expiry.** Set
  `LUMNIK_AUTH_VERIFY_WITH_IDP=true` and the hub asks the identity provider on every request. It
  costs one call per request; off by default.
- **The audit trail cannot be edited or erased by the request-path role** (the migration above).
- **A refused configuration reaches you as a refusal, not as a `500`.** Whatever the door thought
  was wrong, it now says so.
- Every refusal is logged at `WARN` — previously some were, some were not.
- Credentials written inline in a manifest now draw an advisory that a successful `apply` hands
  back, instead of applying silently.

### Added
- **Cancel a running ingestion.** `lm run cancel <id>`, `c` on the TUI's `:runs` view, or
  `POST /api/platform/runs/{id}/cancel`. The run stops at the next chunk boundary; what was already
  written stays written.
- **A run says what it is doing, in the hub's log.** It announces itself at start and at every end,
  and each of its log lines carries the run id and the tenant — so two concurrent runs can be read
  apart. A run whose position stops moving is noticed rather than left turning.
- **The run ledger names the endpoint each row was read from**, so a multi-endpoint connector's
  history can be read per source.
- **A REST source's declared total becomes the run's witness** — when the API says how many records
  exist, the run checks itself against that number.
- **Integration as code.** Every `-f` door accepts a manifest on stdin, so a manifest can be piped
  from a generator or a secret store instead of landing on disk first. The discovery commands hand
  back data rather than a rendering, which makes them scriptable.
- `lm chat` accepts `:scope TAG`. An entity can declare the name it is called by, and a view
  inherits it as its title.

### Fixed
- **JDBC** — the reader pages on the whole primary key, so a composite key no longer skips or
  repeats rows. The persisted cursor reaches the last row: small tables are incremental too, and
  there is no tail re-read on every run. The guided `lm source jdbc add` refuses a table it cannot
  ingest, works in the schema you declared, and leaves nothing behind when it refuses. Secrets typed
  at a prompt are never echoed, and the terminal is restored if you interrupt with `Ctrl+C`.
- **A run could be left `Running` forever** — a transactional read was rewriting rows that carry
  JSON, which orphaned the run that owned them.
- **REST / GraphQL / SOAP** — a dead source no longer reads as a successful empty sync; the
  pre-flight test knocks the way a real run knocks, so it stops passing where the run fails; an
  authentication failure reports the request that was actually composed, headers included; a naive
  ERP timestamp lands instead of being refused, and when the database does refuse one, the reason
  reaches you.
- **`lm run list`** narrows at the hub rather than in the terminal, and says so when a page is full.
- An entity `apply` refusal carries the message it promises. A workflow can declare a lifecycle over
  the column name the source actually chose. The `apply` doors tell you about the scopes they are
  about to overwrite.
- The JDBC, REST, GraphQL and SOAP documentation pages were rewritten over benches you can run
  yourself, and the security section now reads like the rest of the site.

## v1.6.2 — 2026-09-07

The same tree as upstream v1.6.0 for everything that ships here, published under the next free
number: this edition's release proofs refused v1.6.0 and v1.6.1, each time on one upstream test
that could only run in the monorepo. Both tests are fixed; nothing that ships here changed. Read
the v1.6.0 entry below for what is new.

## v1.6.0 — 2026-09-07

Six days of using the product on a deployed stack, sixty-six fixes upstream. Two migrations run
at startup (identity V16–V18); one manifest field changes name — read **Changed** first. The
chat surface is a closed module and its changes are not listed here.

### Changed — read before upgrading
- **A connector's métier scope is declared in `metadata.scopes`.** The two older fields,
  `metadata.tags` and `spec.target.tags`, are refused at `lm apply` with the line to paste in
  their place. Rename the field; nothing else in the manifest moves. `lm connector tag` /
  `retag` became `lm connector scope ls|set|add|rm|sync`; `lm csv ingest --tag` is gone and
  `--scope` may be repeated. Stored data, JSON fields and API routes are unchanged, so an older
  `lm` keeps working against this hub — but a `scopes:` manifest applied to an **older hub** is
  accepted and silently unscoped. Upgrade the hub before the manifests.
- `lm run get`'s cell-error line now reads *"N cell(s) a hook could not process — the rows were
  still written"*: a hook may leave a value intact and still count it (see `fix-mojibake` below).
- `to-uuid` gains `on-invalid: null | generate | keep` for a cell that holds something
  unreadable. The default is `null`; a manifest that relied on `generate: true` minting an id
  over junk like `N/A` must now say `on-invalid: generate` — no invented identifier unless asked
  for by name.

### Security
- **The caller's identity comes from the token, never from a request header.** Five hub doors
  read an `X-User-Id` header the caller could write: one let any user read and change another
  user's default scope; manual DLQ discards and manual runs were recorded in the audit trail
  against user 0. The header is no longer read anywhere.
- **The event stream (`/api/platform/events/stream`, the TUI's live feed) returned every
  tenant's events to any integrator** — run errors, secret names, business row values. It is now
  bound to the caller's tenant.
- A user bound to some scopes could apply a connector manifest declaring scopes they do not hold;
  the connector door now refuses with the same `403 scope not granted` as the entity door.

### Fixed
- **Some reads answered empty or 404 under row-level security** because the tenant binding is
  transaction-scoped and those doors read outside one: `lm secret list` printed `(no secrets)`
  over a stored secret, `lm secret describe` 404'd, `lm integration test` answered 404 on every
  hub, org create/update failed every parent lookup, and the superadmin's tenant list counted
  0 orgs / 0 users for every other tenant. All corrected; a build-time check refuses any new
  door of that shape.
- **A fresh install had no user row for the bundled `integrator`**, so `lm scope set` answered
  404 and every door that loads the user behaved as if nobody were there. The install now seeds
  it (V16), seeds tenant 11 with `hubadmin` — which had never been able to log in (V17) — and the
  PWA's client emits the same `user_id` claim as `lm`, so both reach one hub row.
- **A `kotlin` cell hook could exhaust the hub's memory** on a large file: the script engine
  retained a generated class per evaluated row. Scripts are now compiled once and evaluated per
  row — 2 000 evaluations went from 42.8 s to 12 ms with no retained memory. Same for
  `after_process: custom_kotlin` file hooks.
- **CSV rows and hooks no longer lose values silently:**
  - a row with more values than the header declares is quarantined to the DLQ with its raw
    values, not trimmed and counted as written;
  - `split` keeps trailing empty parts, creates every declared column (`null` when a part is
    missing) and counts a value whose part count differs from `into`;
  - `fix-mojibake` declines a cell it cannot repair losslessly (CJK, `€`, emoji beside
    double-encoded Latin text) and leaves it as it was, counted — it used to write `?`; it also
    repairs past the first line of a multi-line cell;
  - `lower` / `upper` / `titlecase` touch text cells only (a date, an amount or bytes pass
    through untouched, so the column keeps its type) and no longer follow the server's locale;
    the REST `TO_LOWER` / `TO_UPPER` promotes likewise;
  - `regex-extract` refuses a bad `groups` key at apply time instead of silently skipping it — a
    negative key used to abort the whole chunk — and a non-match still creates the declared
    columns as `null`, counted;
  - `map-columns` refuses the four bad rename shapes at the csv `lm apply` door and at the flat
    `lm connector apply` door, as the jdbc door already did; a renamed column whose value is
    `null` keeps its place instead of vanishing from the table;
  - a `lookup` whose `table:` is not a map and a `filter-where` with a bad regex are refused with
    the hook and knob named, instead of a silent no-op or a stack trace;
  - a malformed hook entry (`- [a, b]`) is refused in a csv mapping as it already was in a jdbc
    one, instead of being dropped.
- **Deleting and re-creating:** `lm connector delete` on a table feeding a fused entity answered
  500 — it now refuses with a 409 naming the entity and the gesture, and `lm entity delete
  <name>` exists to perform it; re-ingesting a deleted one-shot under the same name 500'd; a
  one-shot's dormant endpoint was missing from `GET …/endpoints` and `lm endpoint purge` on it
  500'd.
- **Re-ingesting under an existing name** is now a gesture, not an error: `lm csv ingest` on a
  name that belongs to an earlier one-shot re-reads the file into the same table (rows dedup by
  the stored key, new columns added), and the result line says so. A name that belongs to a
  configured connector still refuses with a 409 and the two ways out.
- `lm connector apply` given a CSV, or a `kind:` manifest, now says which it is and names the
  command that takes it (`lm csv ingest` / `lm apply`) instead of a YAML unmarshal error or an
  anonymous 500. The CLI reference no longer calls the two apply doors the same upsert.
- Every date column in `lm` tables and the TUI renders in your local timezone (13 sites);
  `lm connector list` gains a LAST RUN column so a stale `Failed` carries its date.
- `lm dlq list --run` and `--connector` accept the 8-character run prefix and the connector
  name; `lm run list` is newest-first; `lm connector pause` / `resume` print what they did;
  `lm dlq list` shows the connector's name next to its id.
- Every hub 404 carries a body naming what was missing (`lm` used to print "no details from the
  server"); an unmatched route answers JSON instead of an HTML page.
- `deploy/selfhost/up.sh`: the bootstrap superuser named in `.env` is verified against the
  database volume on every run; a regenerated `.env` claiming a role the volume does not hold is
  corrected instead of believed.
- The TUI's live event tail could skip events committed in the same instant as one already
  shown; it now cursors by id.
- A one-shot's `ext.*` table grouped its grants so that one missing role silently cost the
  read-only role both its `SELECT` and its schema `USAGE`; each grant now stands alone.

### Added
- `lm entity delete <name>` · `lm connector scope …` · `make -C lm install` (build and install
  `lm` into `~/.local/bin` in one step) · `to-uuid: on-invalid` · a bad hook parameter is refused
  before the first row, even on an empty chunk.
- Docs: real `lm` screens on the integrator pages (login, apply, run, DLQ, secrets, sources,
  views…); *Transformers* rewritten with every hook's input → output table and one real manifest
  running all of them (`docs/connectors/csv/60-s3-every-transformer.yaml`); *CSV & files* with
  every transport's screens and the drop-folder cursor traced run by run; the from-source upgrade
  loop and its trap (`docs/operations/upgrade.md`).

## v1.5.3 — 2026-09-01

An evening of real first-install testing; every catch fixed the same day.

### Fixed
- A fresh install without SMTP configured failed to start (the compose file passed an
  empty mail host, which the platform now refuses at boot). No action needed: the
  default is inert while mail delivery is mocked, the install default.
- `lm csv ingest` without `--name` created tables and connectors named after an internal
  temporary file (`ext.resteasy_reactive…upload`). The table, the connector and the
  endpoint now carry your file's name, as the `--name` help always promised.
- `lm connector delete` on a CSV one-shot removed the connector but left its `ext.`
  table behind. Ext tables now die with their connector, one-shots included.
- Numeric CSV columns mixing whole and decimal values (or containing blanks) were
  promoted as TEXT; they now promote as NUMERIC. Re-ingest to pick up the better types.
- Re-running `deploy/selfhost/up.sh` on an install whose database volume predates the
  current init scripts could leave ingestion failing with `permission denied`; `up.sh`
  now re-applies the (idempotent) grants on every run.

### Added
- A new recipe, *Analyze the database directly*: connecting psql, pgAdmin or IntelliJ
  to your install's PostgreSQL — the three database roles, the safe port-forward, and
  the one habit that makes your session see what the product sees.

## v1.5.2 — 2026-09-01

One installer fix, found during a real first install of this edition.

### Fixed
- **Re-running `deploy/selfhost/up.sh` on a fresh install broke the stack**: the script
  guessed the Postgres bootstrap superuser from the age of `.env` and could pin the
  legacy name onto a new volume, after which the password-alignment step failed with
  `FATAL: role "adempiere" does not exist`. It now records the name at first install
  and, for older installs, asks the running database which name the volume was
  initialized with. If you hit this: set `POSTGRES_USER=lumnik` in `.env` (or remove
  the line) and re-run `up.sh`.

## v1.5.1 — 2026-09-01

One fix, found by this edition's own release proofs before v1.5.0 could ship here —
which is why the open edition goes straight from v1.4.1 to v1.5.1.

### Fixed
- **Scope-bound reads (view render, view detail, workflow data-truth, ask execution)
  answered 500** on the Quarkus 3.33 platform: the connection pool now refuses a JDBC
  read-only toggle inside a transaction, and the tenant-scoping path used one. The toggle
  is removed; read-only-ness is enforced where it always was — by the `lumnik_readonly`
  database role (`default_transaction_read_only=on`, SELECT-only grants). An integration
  test now exercises the exact production posture.

## v1.5.0 — 2026-09-01

The platform catches up. Nothing to migrate: no schema change, no API change, the fast-jar
layout and every operator gesture are untouched.

### Platform
- **Quarkus 3.17 → 3.33 LTS** (upstream-patched until March 2027; Java 21 unchanged), carrying
  Hibernate ORM 7, Testcontainers 2 and the aligned extension ecosystem.

### Security
- **Ed25519 SFTP host keys now ride Bouncy Castle.** The unmaintained `net.i2p.crypto:eddsa`
  (Ed25519 signature-malleability advisory, never patched upstream) is removed. Host-key
  verification behaves exactly as before — and an integration test now asserts the negotiated
  key really is ed25519, so a silent fallback to RSA cannot pass unnoticed.

### Fixed
- `GET …/deliveries?limit=0` returned a 500 instead of an answer; the limit now clamps.
- A per-tenant hook-script edit could accumulate compiled scripts in memory indefinitely;
  script caches are bounded.
- The hub module build ran every Quarkus build step twice; builds are roughly 2× faster.

## v1.4.1 — 2026-08-30

Fixes only. Nothing to migrate, no schema change, no API removed.

Most of what follows came out of a single exercise: folding families of near-identical tests into
parameterized tables, then reading the axes the table exposed. Twelve defects surfaced that way, and
nine were reproduced by running the new rows before anything was fixed.

### Ask-to-SQL

- **The 200-row bound was not always applied.** The guard matched patterns against the model's raw
  output, so a query ending in a `--` comment received its `LIMIT 200` *inside* that comment and ran
  unbounded. Row-level security and the read-only datasource still held, so no data crossed a tenant
  boundary — but the bound this project promises did not hold. Both guard layers now read lexer
  tokens rather than text.
- **Honest questions were refused.** Reading raw text, the same guard blocked a customer in a town
  called Grant, a note containing the word "update", and a reference `A;B`. A `;` inside a string
  literal is a character again, and a quoted `"insert"` is a column name.
- **`NOT IN` was refused under a column that exists in no schema.** `WHERE segment NOT IN (…)` built
  its filter on the operator instead of the column, so the refusal named `"NOT"` — a daily shape of
  model output, refused for a reason the reader could not act on.
- **An accent split an identifier in two.** `WHERE crédit = 'X'` was refused as `"dit"`: the lexer
  read ASCII only, so an accent ended one identifier and the letters after it began another.
  Identifiers are Unicode now; numbers stay ASCII, because a foreign digit is not a page size.

### Ingestion

- **`split` with `into-array` produced a value that could not be stored.** The documented option
  puts a list in the row; it reached the `jsonb` column as `character varying` and Postgres refused
  it. A nested map failed earlier still, on hstore. Either way the chunk failed, the cursor did not
  advance, and the retry replayed the same failure. Both shapes now land as real `jsonb`, queryable
  with `@>` and indexable with a GIN index — when the chunk that creates the column carries a value.
  When it is null-only there, the column is typed `text` and stays that way; column types are never
  widened.
- **Four cell hooks crashed on a malformed manifest.** `truncate`, `mask`, `regex-replace` and
  `regex-extract` threw raw runtime errors on a bad parameter instead of refusing the manifest the
  way every other validator does.
- **`kind: Entity` silently ignored keys it does not honour.** A misspelled key was accepted and
  dropped. It is now named.
- **`default_rate_limit` was validated by nobody.** The value was read by the limiter and checked by
  no validator, so a nonsensical setting reached the engine intact.
- **A chunk template rendered `_id` as empty** into every chunk it produced.

### Métier scope and identity

- **The scope boundary did not reach three app-ladder doors.** Entity apply, process apply and the
  process manifest store answered a scope-bound caller as if the caller were unscoped. They now give
  the same uniform refusal as every other scoped door.
- **The tenant in the URL is now compared to the caller's own.** Ten identity endpoints took the
  tenant from the path without checking it against the caller's claim. Row-level security already
  blocked the rows — a cross-tenant request found nothing and returned `404` — so nothing was
  readable that should not have been. What changes is that the refusal now says what it means:
  `403`, not a `404` that misdescribes why.

### Tests

These are not behaviour changes, but this repository ships its tests and they are meant to be
read. Six inventory
tests were added: each enumerates its axis from the production source and fails in both directions,
so a new subject cannot arrive unguarded and a stale exemption cannot outlive its reason. A registered
after-each callback now rolls back any transaction a test leaves open, so one failing test can no
longer make an unrelated one fail after it.

## v1.4.0 — 2026-08-28

### Breaking

- **The business value-lists API is removed.** Migration `core/V42` drops `core.list_definition` and
  `core.list_value`; the `/api/tenants/{tid}/lists…` endpoints and the `list.value.*` events are gone
  from the catalog. Nothing in the platform ever read those tables — no ingestion, mapping,
  ask-to-SQL, view or workflow path consulted a value, and no enum carried the `@ListMirror`
  annotation its bootstrapper scanned for. **Export your rows before upgrading if you wrote any.**
  What that API reached for now exists in `kind:Workflow`: declared states, aliases for dirty legacy
  values, transitions.

- **A batch the writer refuses wholesale now fails the chunk** instead of reporting success. Before,
  a `WriteOutcome.AllFailed` left its rows outside the conservation identity
  (`records_in = records_out + records_skipped`), sent nothing to the dead-letter queue, and let the
  cursor advance past rows nothing had written. It now behaves like a read or prepare failure: no
  counters, no cursor movement. **Read this one if you build connectors on `BulkItemWriter` or
  `CopyItemWriter`** — they are the two writers that can return `AllFailed`.

### Fixed

- A window that yields no usable row but isolates malformed ones now counts them on both sides of the
  conservation identity and dead-letters them, instead of dropping them silently.
- Two authentication guards on `/api/platform/rag/*` now actually execute. They lived as nested test
  classes, which the test runner discovers by file name and therefore never ran; one of them also
  expected `403` where an anonymous request correctly gets `401`. A repository check now refuses that
  shape so a third cannot appear.
- A stream could leak on the CSV reader's fallback path. Latent — an upstream guard makes that branch
  unreachable today — but the trap is removed.

### Quality

- Blocker and critical static-analysis findings **25 → 2**; cognitive-complexity criticals **7 → 0**;
  reliability rating **E → A**. Reliability, security, security-review and maintainability all **A**;
  0 bugs, 0 vulnerabilities, 0 security hotspots to review.
- 2069 tests, 0 failures.

### Verified for this release

`mvn verify` on the extracted tree (freshly created, so there is nothing to clean) · `lm` builds
and its unit suite · the repository's guard
scripts · a from-scratch boot of the open edition · secret scanning. A red proof aborts the
publication before anything is pushed.

## v1.0.0 — 2026-08-26

First publication of the open edition.
