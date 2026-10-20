# Changelog — lumnik (open edition)

The open edition is a projection of the lumnik monorepo, republished per release. Version numbers
are aligned with it from v1.4.0 on: open-edition `vX.Y.Z` is the projection of monorepo `vX.Y.Z`.
Releases 1.1–1.3 existed upstream and were never published here — absent, not withdrawn.

This file lists only what ships in this repository. Closed modules (the RAG store, the langchain4j
binding, the Kafka connector, SCIM) are not part of it, and changes to them do not appear here.

## v1.9.0 — 2026-10-20

A hub table now holds what its source holds: one row per key for a database table, the file's rows
and no others for a single CSV file. Dates keep their type through an entity, align across sources,
and read the same whatever zone the hub runs in. `lm ui` becomes the integrator's workbench: every
kind can be browsed, created and acted on without leaving it. A caller bound to a métier is now
bound everywhere: connectors, runs, dead letters, workflows and the decision inbox.

### Before you upgrade
- **Take a backup.** This release carries six migrations (V70–V75), and migrations are
  forward-only. See `docs/operations/upgrade.md`.
- **Plan for a lock at first boot.** V73 rebuilds the unique key of every existing `t_*` table as
  `(tenant_id, _row_hash)`: one index per table, and every table stays locked until the migration
  commits. Time it on a copy if your tables are large.
- **Add `--force` to scripts that delete.** `lm connector delete`, `lm entity delete` and `lm search
  rm` now ask you to type `delete`. Without a keyboard they stop: `aborted: no confirmation read
  (EOF) — pass --force in a script`, and nothing is deleted.
- **Stop using `lm migration apply` and `lm mapping`.** Both are removed. The hub migrates at every
  start; the mapping template surface acted on nothing (V70 drops its table).
- **Check manifests the validator now refuses.** Each of these used to pass `lm validate` and fail
  every run:
  - a `jdbc-generic` table with no `schema`, on the table or in `defaults`;
  - an `sftp` csv-file with a strict host key (the default) and no `host_key_fingerprint` (the hint
    gives the `ssh-keyscan` command for your host and port);
  - a REST endpoint with `pagination.kind: custom`.
- **Helm: OpenTelemetry is now off by default**, as on self-host. If your hub exported traces, add a
  `hub.extraEnv` entry `QUARKUS_OTEL_SDK_DISABLED=false`.

### After you upgrade
- **Watch the first run of each `jdbc-generic` table.** It moves the table to one row per key:
  the newest version of each key stays, the others are removed, and `lm run get` reports how many.
  Two different rows under one key now fail the run naming `pk_columns`: declare the source's real
  primary key. A key combined with an `on-conflict` other than `SKIP` is refused.
- **Watch the first run of each single-file CSV connector.** A `csv-file` reading one local file
  (no glob, `after_process: none`) now removes the rows the file no longer holds. A run that would
  remove more than 30% of the table fails and removes nothing; `spec.refresh.max_removed` changes
  the limit (`off` disables it).
- **Re-apply your entities** (`lm entity apply -f …`, same manifest). A view is compiled at apply,
  so an existing entity keeps its old rules until then. After it:
  - each source key is read from the newest run that brought it, so an updated source row no
    longer conflicts with its own old version;
  - a column every source holds as a date stays a `date`, and dates mixed with timestamps become a
    `timestamptz` (a date is its midnight UTC). A column with one text source stays text;
  - a source's day and another's time on that same day agree instead of conflicting;
  - a join key that is a date or a time finds a day-keyed lookup row.
- **If your hub does not run in UTC, expect dates to read differently.** Views, view filters,
  process rules and messages, entity reads and raw rows now all read and write in UTC, so the same
  filter picks the same rows everywhere. The published Docker image runs in UTC.
- **If a webhook or n8n flow parses timestamps by exact format, check it.** View JSON and the
  process payload `fields` now carry `2026-07-01T00:00:00.000500Z` (microseconds kept) instead of
  `2026-07-01T00:00:00.000+00:00`. Both are RFC 3339.
- **Review notifications and webhooks if you use `scope:*` roles.** Notification rules,
  integrations, deliveries and the event stream are now tenant-wide administration: a caller
  bound to a métier is refused them. Existing subscriptions keep firing; `lm notify list` and
  `lm integration list` show what is there.
- **A credential now leaves with the last connector that uses it.** The delete confirmation names
  it.
- **SOAP `{{secret:NAME}}` now reads `lm secret` first, then the environment.** A tenant secret
  outranks a hub variable of the same name; write `{{env:NAME}}` to read the environment only.

### Added
- **`lm ui`, the integrator's workbench.** New pages: `:entities` (rows, identities, history),
  `:tables` (the `t_*` tables with real row counts and their raw rows), `:views`, `:apps`,
  `:workflows`, `:exports`, `:search`, `:integrations`, `:notify`, `:secrets` and `:context` (switch
  hub without leaving). `:new` creates every kind, validating before it applies. A JDBC source is
  onboarded from the TUI, a REST endpoint is pre-flighted and probed there, and `/` filters most
  lists. Every action runs off the screen's loop: a slow hub no longer freezes a key.
- **A jdbc connection declares its zone.** `spec.connection.zone: Europe/Paris` reads a source's
  `timestamp` (no time zone) in that zone, each time at its own offset. Without it, such times are read as UTC, as before. To move rows already landed:
  `lm apply`, `lm endpoint reset-cursor`, `lm connector run`.
- **jdbc `source.tables[].target`** names the hub table a source table lands in.
- **Delete says what goes before it asks.** `lm connector delete` names each table, its rows and the
  credential that goes with it; `--dry-run` on connector delete and `lm endpoint purge` show it
  without deleting.
- **The audit trail records connector, entity, view, workflow and process writes.**
- **New commands**: `lm entity list`, `lm app get NAME` (an applyable manifest), `lm view NAME
  [KEY]` (a saved view by name), `lm outputs replay ID`.
- **Settings that reach the hub**: rate limits, the workflow sweep and OpenTelemetry can now be set
  through `.env` on self-host and `hub.extraEnv` on Helm.

### Changed
- **A métier-bound caller is bound everywhere.** Connector doors (get, update, delete, run, pause,
  endpoints, tags) act only on connectors whose scopes the caller holds, and lists are filtered;
  so are runs, dead letters, the `lm source` doors, workflow graphs and tape, and the decision
  inbox. Another métier's object is the uniform `403 {"error":"scope not granted"}`. A caller with
  no `scope:*` role keeps tenant-wide administration.
- **A second connector cannot write another connector's table.** Its run fails naming the holder
  and the way out (jdbc: `target`; csv: rename the connector). A sharing that existed before
  keeps working, and deleting one of the sharers removes only its own rows.
- **A row identical to another tenant's is stored** (it was silently dropped while the run counted
  it).
- **A SearchIndex re-apply that removes or changes what is served is withheld** until a rebuild of
  the new declaration cuts over, and an index made invalid by an entity re-apply serves nothing.
  `lm search status` shows `withheld` and `invalid`.
- **The flat connector door (`lm connector apply -f`) creates only what it can run**: `jdbc-generic`,
  `rest-generic` and unknown types are refused naming `lm apply -f`.
- **Errors stop quoting what they should not.** A webhook delivery, a REST probe or run, a database
  failure and a workflow apply now say the kind of failure and its SQLSTATE, never a message that
  can carry a URL credential or a stored value. Rows written before keep their text.
- **Output**: `lm config view` redacts tokens (`--raw` shows them); `lm connector run` prints one
  run id per endpoint; `lm dlq list` shows whether an entry was replayed or discarded; `lm view`
  says when the 1 000-row cap dropped rows; `lm secret set` without `--description` keeps the
  stored one; the `lm inbox` header reads `WHEN`.
- **Refusals that were 500s**: a taken connector name is a 409 naming its holder; a malformed CSV
  row in a one-shot ingest is a 400 naming its line; a body over 10 MB says the limit; an
  integration in `HMAC` or `BEARER` mode without a secret is refused; a schedule on `lm source jdbc
  add` must be a 5-field cron.

### Fixed
- A connector delete no longer removes a table another connector shares, or another tenant's rows.
- A process rule on a `DATE` column fires on the day it names, whatever the hub's zone.
- Several replicas no longer copy an audit row twice.
- `lm migration status` no longer fails on a schema with no applied migration.
- A SOAP or GraphQL endpoint without `method` is stored as `POST`, the method it is sent with.

## v1.8.1 — 2026-10-15

What the documentation already showed is now in the binary: export files shaped for a partner,
corrections that name a row the way you see it, and a time zone you can declare. And a date and
time with no time zone is now stored the same way wherever the hub runs.

### Before you upgrade
- **Nothing to do.** No migration, no new setting.

### After you upgrade
- **If your hub runs from the jar on a machine not set to UTC, read this.** A date and time with no
  time zone used to be stored in that machine's zone: `23:30` landed as `21:30` UTC on a machine in
  Paris in summer. It is now stored as UTC. This concerns `parse-date` with a time, and a
  `jdbc-generic` source column of type `timestamp`.
  - The published Docker image runs in UTC and was never affected.
  - Rows already landed keep their value until their source row changes.
  - If the source's times are in a known zone, say so: `parse-date: { …, zone: Europe/Paris }`.

### Added
- **Exports shaped for a partner.** A column can be renamed in the file (`as:`) and formatted:
  `lookup` (your codes to the partner's), `format-date`, `format-number` (decimal comma, fixed
  decimals), `mask` (hide personal data). `spec.filter` keeps only some rows. A value a formatter
  cannot write fails the run and nothing is published. Existing export manifests are unchanged.
  See *Shape the file for a partner* in the exports page.
- **`parse-date` takes `zone:`**: say which time zone the source's times were written in.
- **Name a row by what you see.** `lm entity assert-same` and `lm entity assert-distinct` take
  `<source table>:<source id>`, the same pair `lm entity history` takes:
  `lm entity assert-same Customer --left t_res_crm:9003 --right t_res_erp:C-003 --reason "…"`.
- **A guide to entity resolution** for the installer, with every screen from a live hub
  (*Entities — resolve*).

### Changed
- `lm` 1.4.1 (the assertion handles above).

## v1.8.0 — 2026-10-15

The hub now sends data out as well as taking it in. Three capabilities land whole:
- **Entity resolution** gives the same customer or product one identity across systems that never
  shared a key, and lets a person correct it.
- **Managed exports** deliver an entity as CSV to SFTP or S3, on a schedule or on demand, without
  ever overwriting a file.
- **Queued runs** let a busy endpoint wait for its turn instead of refusing.

Views read past their old 1 000-row cut. And a long list of hot paths stopped holding a database
connection or reading a whole table when they didn't need to.

### Before you upgrade
- **Set `LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET`.** The hub refuses to start without it: 16
  characters or more, not the placeholder.
  - `deploy/selfhost/up.sh` adds it to your existing `.env` for you.
  - With Helm, set `secrets.entityResolutionHmacSecret`.
  - Keep it with your other secrets: changing it later invalidates every resolution fingerprint and
    every open page link in a view.
- **Take a backup.** This release carries 21 migrations (V49–V69), and migrations are forward-only.
  See `docs/operations/upgrade.md`.
- **Delete any duplicate SearchIndex you don't want.** If two SearchIndex manifests target the same
  entity, the upgrade keeps the earliest valid one and purges the other.
- **Decide on webhook history.** Deliveries older than 90 days are purged at the first 05:00 run
  after the upgrade, and can no longer be replayed. Set `LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS=0`
  to keep everything.
- **The database image is now `pgvector/pgvector:0.8.6-pg16`** in the compose and Helm files.
- **Re-running `deploy/helm/up.sh` over a live cluster now refuses.** It used to regenerate every
  secret, including database passwords the database never learned.
  `LUMNIK_ALLOW_SECRET_REGENERATION=true` is the explicit way through.

### After you upgrade
- **Re-apply some entities with the same manifest** (`lm entity apply -f …`): every entity that
  declares `resolution:` or `joins[]`, and any entity a SearchIndex observes. Their views changed
  shape.
- **Rename reserved columns.** `_entity_id`, `_resolution`, `_source_id` and `_handle` are now
  reserved column names; a manifest that uses one is refused at its next apply.
- **Check values landed by rewriting hooks.** Before this release, rewriting cell hooks ran twice on
  every `csv-file` and `jdbc-generic` chunk: `regex-replace` could land `X-X-42`, and `split` came
  out mangled. Check data such hooks wrote.
- **Fix scripts that relied on `lm` exiting 0.** An unknown word after a command group (`lm
  connector lst`) now exits 1 instead of printing help and exiting 0. `lm export run` now reports
  `accepted`.

### Added
- **Entity resolution.** In a `kind:Entity` manifest, `resolution:` declares how rows from different
  sources become one entity: an ordered cascade of exact rules over normalised fields.
  - **What it never does**: an ambiguous match never merges, and a resolved link is never moved
    silently.
  - **Reviewing**: `lm entity ambiguous NAME` lists what needs a human, and `lm entity history NAME
    SOURCE ID` explains a decision, including which entities were set aside and why.
  - **Correcting**: `lm entity assert-same` / `assert-distinct` record a correction that survives
    every replay. Retracting a `same` splits the identity back off.
  - **Replaying**: `lm entity resolve NAME` replays the whole entity.
  - **In the TUI**: `:ambiguous` shows the same list.
  - **In the entity view**: rows carry `_entity_id` and `_resolution`, and unresolved rows carry a
    `_handle`, so `lm conflicts list` can name every row.
- **`kind:Export` — deliver an entity as CSV to SFTP or S3.**
  - **The file**: deterministic bytes (UTF-8, CRLF, a stable order).
  - **Publishing**: always atomic and never over an existing file. SFTP uses a `.part` file and a
    rename, with the host key always verified; S3 uses a conditional multipart upload with
    checksums.
  - **Running**: on a cron schedule (latest slot only, no back-fill) or on demand, with a ledger of
    every run.
  - **Commands**: `lm export apply|list|get|run|retry|runs|status`, and `lm entity export NAME -o
    FILE` for a one-shot download.
  - **Docs**: `docs/apps/exports.md`.
- **Queued runs** (`LUMNIK_RUN_QUEUE_ENABLED=true`, off by default).
  - **What changes**: a manual connector run, or an on-demand export, that meets a run already in
    progress waits as `Queued` and starts when the endpoint is free.
  - **Cancelling**: `lm run cancel` ends a queued run.
  - **Before turning it on**: upgrade every replica first; `docs/operations/upgrade.md` says why.
- **Views read past 1 000 rows.** `POST /api/views/render` pages with `pageSize` and an opaque
  `cursor`.
  - **The app's Fiches tab**: it pages, with the pager above and below the results, and filters
    through typed controls built from each column's kind.
  - **Filters that can never match** on a column's type are refused with a 400, instead of
    returning an empty table. Scientific notation (`1e-5`) is accepted.
  - **`spec.links`**: a cell can lead to a filtered view or to an external page.
- **`joins[]` on `kind:Entity`.** A many-to-one lookup table enriches an entity after fusion: no
  match gives NULL, several matches give NULL plus a conflict entry. Keys compare by numeric value
  (`1000021` joins `1000021.0000`).
- **JDBC dialect providers.** PostgreSQL is now a provider behind `lumnik-jdbc-spi`, and a connector
  resolves its dialect strictly: an explicit `dialect:` wins, otherwise exactly one provider must
  accept the URL. `docs/connectors/jdbc/writing-a-provider.md` explains how to write one.
- **Choosing what an app shows.**
  - **`spec.tabs`**: on a `kind:App`, it lists the tabs that app shows. A tab this hub does not offer
    comes back as a warning, never a refusal.
  - **Seeing the result**: `lm app list` shows your apps, and `lm module list` shows each module
    with the reason it is active or not.
  - **Docs**: "Choosing what your hub offers".
- **Proposal dossiers.** `POST /api/proposal/check` replays a prospect's dossier against your real
  data, read-only. `lm proposal draft` turns it into a `kind:Workflow` draft, and never applies it.
- **SearchIndex declarations.** `lm search apply|rm|query|status|rebuild` and the
  `titleField`/`links` keys. This edition carries no Search provider, and
  `GET /api/search/capability` says so.
- **Webhook delivery retention.** `LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS` (default 90) bounds the
  delivery journal, which used to keep every payload and response forever.

### Changed
- **Webhooks and email are sent after their batch commits**, with no database transaction held
  across the remote call.
  - **Webhooks**: delivery is at-least-once, and `X-Lumnik-Event-Id` is the idempotency key.
    HMAC/Bearer retries that used to fail now deliver.
  - **Email**: a crash between commit and send loses that batch's mails instead of sending them
    twice.
  - **Failures**: one failing event, or a NUL byte in a receiver's answer, no longer stalls the
    consumer.
- **The outbox drains far faster**: about 6 200 events per tick instead of 100.
- **Local CSV says when something is wrong instead of ending quietly.**
  - **A missing cursor file**: if the cursor's file is gone while later files wait, the run fails
    and names the fix, `lm endpoint reset-cursor <connector>/<endpoint>`.
  - **A file removed mid-run**: a listed file removed before the run reaches it fails the run.
  - **Already-read files**: a fully read, unchanged file is not read again.
  - **New files**: the folder is listed once per run, so a file dropped mid-run waits for the next
    run.
- **Remote CSV (SFTP/S3) keeps one connection per run** instead of one per chunk, and relists every
  100 files. Your SFTP server's idle timeout and session cap now apply to a whole run.
- **Scheduled runs and exports are bounded per instance**: 4 and 2, set with
  `lumnik.connector.max-concurrent-scheduled-runs` and `lumnik.export.max-concurrent-scheduled-runs`.
  Runs beyond the bound wait instead of failing on pool timeouts.
- **Faster where it was slow**, each change measured in its PR: the runs list, the data freshness
  indicator, the inbox, the delivery pages, JDBC chunk fetches, row hashing and date parsing, and
  repeated REST reads of unchanged rows.
- **Inbox acks**: only an ack written by `POST /api/inbox/{id}/ack` folds a decision; a hand-emitted
  ack event no longer does.
- **`lm source jdbc discover --skip-pattern`** defaults to empty; the dialect applies its own
  defaults.
- **The app no longer shows a Chat tab** in this edition, where it never worked.

### Testing
- **OTK silver**: 182 of 182 scenarios with 0 findings on this tree. The managed-export delivery case
  needs a real SFTP destination and stays dormant without one.
- **`lm` binaries**: this release ships `lm` **1.4.0** (archives and `checksums.txt` alongside).

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
