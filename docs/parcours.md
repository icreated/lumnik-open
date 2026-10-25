# Guided path — the guided path

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Guided onboarding</p>
<p class="lm-lead" markdown="span">From an empty machine to a running hub, a fused entity, scoped chat that cites its sources, and a workflow that flags what stopped moving — in three half-days.</p>
<div class="lm-meta" markdown>
<div class="lm-meta-item" markdown>
<strong>Audience</strong>

An integrator or tester discovering lumnik — nothing assumed beyond Docker and a terminal.
</div>
<div class="lm-meta-item" markdown>
<strong>Format</strong>

Three half-days. Every step gives a goal, the page to follow, and a checkpoint.
</div>
<div class="lm-meta-item" markdown>
<strong>The spirit</strong>

Do the steps — don't just read them.
</div>
<div class="lm-meta-item lm-meta-item--accent" markdown>
<strong>Before you start</strong>

Docker, a terminal, and [Ollama](https://ollama.com) for the steps marked **Pro**. Go 1.26+ only if you build `lm` yourself. The full list: [Prerequisites](quickstart/prerequisites.md).
</div>
</div>
</section>

<div class="lm-pane lm-pane--accent" markdown>
<div class="lm-kicker">The one rule</div>

Run every command **from the repository root** unless a page says otherwise.

```bash
git clone https://github.com/icreated/lumnik-open.git && cd lumnik-open
```

That repository is the **open edition**. A step marked **Pro** needs lumnik Pro with the AI on
(the chat, `lm ask`, the models); on the open edition, read it and skip its checkpoint.
See [The two editions](editions.md#feature-by-feature).
</div>

## The three half-days at a glance

<div class="lm-grid" markdown>
<article class="lm-card" markdown>
<div class="lm-kicker">Half-day 1</div>
<div class="lm-title">Defrost reading</div>

Deploy the hub, meet `lm`, run the WOW, and prove that ingestion stayed read-only and lost nothing.

<p class="lm-note">Six steps, ≈ 3 h</p>
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Half-day 2</div>
<div class="lm-title">Defrost reasoning</div>

Understand entities, the ask guards, scopes, models, and the TUI an integrator operates from.

<p class="lm-note">Six steps, 3 h</p>
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Half-day 3</div>
<div class="lm-title">Defrost time</div>

Move from a mirror of the source to views, processes, workflows, inbox decisions, and the PWA.

<p class="lm-note">Six steps, ≈ 3 h 15</p>
</article>
</div>

## Half-day 1 — defrost *reading*

Goal of the day: legacy data flows into the hub, read-only, and you can prove nothing was lost.

<div class="lm-rail" markdown>
<article class="lm-step" markdown>
<div class="lm-step__num">1</div>
<div markdown>
<div class="lm-kicker">10 min</div>
<div class="lm-title">The pitch</div>

Read the [Home page](index.md): what lumnik is (a read-only defrost of legacy systems) and what it is not (not a migration, not a BI project).

<p class="lm-check" markdown="span">**You know it landed when:** you can explain why lumnik is neither a migration nor a BI project.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<div class="lm-kicker">30 min</div>
<div class="lm-title">Deploy the hub</div>

Decide what the hub carries — [The two editions](editions.md), [Choosing what your hub offers](choosing-what-your-hub-offers.md). Then follow [Deploy lumnik](deploy.md): `./deploy/selfhost/up.sh --build`. Your passwords are in the `.env` it writes, at the root of the clone.

<p class="lm-check" markdown="span">**You know it worked when:** the script ends like this:</p>

```
==> hub is healthy
  hubadmin: per-install password set (HUBADMIN_PASSWORD in .env)
  integrator: per-install password set (INTEGRATOR_PASSWORD in .env)

  Hub:       http://localhost:8080   (health: /q/health)
  Keycloak:  http://localhost:8180/realms/lumnik
  Next:      point lm at the hub — server http://localhost:8080, OIDC http://localhost:8180/realms/lumnik
```
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<div class="lm-kicker">20 min</div>
<div class="lm-title">Meet <code>lm</code></div>

Follow the [Quickstart](quickstart/installer.md): install `lm`, log in, put a first file into the hub.

<p class="lm-check" markdown="span">**You know it worked when:** `lm connector list` shows the connector your first file registered.</p>

```
$ lm connector list
NAME         TYPE      TENANT  SCHED  WINDOW  STATUS  LAST RUN
stock_count  csv-file  1                              
```
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">4</div>
<div markdown>
<div class="lm-kicker">60–90 min</div>
<div class="lm-title">The WOW</div>

Run [The WOW](wow.md): a SOAP Siebel and a CSV fused into one Customer.

<p class="lm-check" markdown="span">**You know it worked when:** `lm entity get customer` lists the fused customers. **Pro:** a question asked in French answers with the SQL it ran.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">5</div>
<div markdown>
<div class="lm-kicker">30 min</div>
<div class="lm-title">How a connector is built</div>

Read [Connectors overview](connectors/index.md) and [CSV & files](connectors/csv.md), the manifest you just used. Every source type follows the same grammar.

<p class="lm-check" markdown="span">**You know it landed when:** you can explain the relationship between manifest, endpoint, and ingestion report.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">6</div>
<div markdown>
<div class="lm-kicker">20 min</div>
<div class="lm-title">What was ingested, skipped, quarantined</div>

Read [Seeing what was ingested, skipped, and quarantined](transparency/ingestion-honesty.md).

<p class="lm-check" markdown="span">**You know it worked when:** on the WOW's `cust-csv` run, you read IN = OUT + SKIPPED. Every skipped row is counted; `lm dlq list` shows the ones set aside.</p>

```
$ lm run get 3e9f353a
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
3e9f353a  cust-csv   default   Completed  8   8    0        0             2026-10-24 21:57  2026-10-24 21:57
```
</div>
</article>
</div>

## Half-day 2 — defrost *reasoning*

Goal of the day: the ingested data answers questions — sources shown, and only to the people entitled to ask.

<div class="lm-rail" markdown>
<article class="lm-step" markdown>
<div class="lm-step__num">1</div>
<div markdown>
<div class="lm-kicker">40 min</div>
<div class="lm-title">Fuse sources into one truth</div>

Read [Entities — fuse](entities.md) and read the Customer entity you applied during the WOW.

<p class="lm-check" markdown="span">**You know it worked when:** you find the `_conflicts` ledger and can say why C-200 is in it.</p>

```
$ lm entity get customer --filter customer_code=C-200
CUSTOMER_CODE  NAME   SEGMENT  EMAIL   CITY  _CONFLICTS
C-200          Marie  SILVER   m@x.fr  Lyon  {"name": [{"src": "cust_siebel", "value": "Marie"}, {"src": "t_cust_csv", "value": "MARIE A."}]}
```
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<div class="lm-kicker">30 min</div>
<div class="lm-title">When Ask refuses · Pro</div>

Read [When Ask refuses](transparency/ask-honesty.md): what `lm ask` says, and what to do.

<p class="lm-check" markdown="span">**You know it worked when:** you can say what to do when `lm ask` answers « la question ne correspond pas au schéma déclaré ».</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<div class="lm-kicker">30 min</div>
<div class="lm-title">Scopes</div>

Read [Scopes](rag/scopes.md): a scope is declared on the connector or entity (`scopes:`) and granted as a Keycloak role `scope:<tag>`. No scope role = tenant-wide; one or more = bound.

The bundled realm has no scope-bound user: create one in Keycloak with the realm role `scope:desk` ([Add a user](security/identity-admin.md#add-a-user), [Grant a business scope](security/identity-admin.md#grant-or-revoke-a-business-scope)).

To work as that second user, give `lm` a second context. Open the login link in a **private browser window**: a normal window signs you in as `integrator` again.

```bash
lm config set-context desk --server http://localhost:8080 \
    --oidc-issuer http://localhost:8180/realms/lumnik
lm config use-context desk
lm login
```

`lm config use-context selfhost` switches back to `integrator`.

<p class="lm-check" markdown="span">**You know it worked when:** a user holding only `scope:desk` is refused the `customer` entity, whose scope is `clients`.</p>

```
$ lm entity get customer          # as a user holding scope:desk
Error: access refused (403): scope not granted
```
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">4</div>
<div markdown>
<div class="lm-kicker">20 min</div>
<div class="lm-title">Models & providers · Pro</div>

Read [Models & providers](rag/models.md): which model answers, and how to swap it (local Ollama or a remote provider).

<p class="lm-check" markdown="span">**You know it worked when:** you know whether your install answers from Ollama or a remote provider.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">5</div>
<div markdown>
<div class="lm-kicker">40 min</div>
<div class="lm-title">The integrator's cockpit</div>

Skim the [CLI & TUI reference](cli/lm-reference.md), then open the TUI with `lm ui`: type `:runs` and Enter, pick a run with the arrow keys, press `d` to describe it. `?` lists every key.

<p class="lm-check" markdown="span">**You know it worked when:** you can find any connector run and its report using only the TUI.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">6</div>
<div markdown>
<div class="lm-kicker">20 min</div>
<div class="lm-title">Who may do what</div>

Read [Roles & permissions](security/role-management.md) and skim [RLS & database roles](security/rls.md).

<p class="lm-check" markdown="span">**You know it worked when:** signed in as `hubadmin` (tenant 11, password `HUBADMIN_PASSWORD` in `.env`), you see none of tenant 1's decisions from the WOW.</p>

Give `lm` a `hubadmin` context and sign in as in step 3 (private window), then:

```
$ lm inbox --all
ID  WHEN  PROCESS  MESSAGE  ENTITY_KEY
```
</div>
</article>
</div>

## Half-day 3 — defrost *time* and the apps

Goal of the day: the hub stops being a mirror and starts finding things — and each team gets an app.

<div class="lm-rail" markdown>
<article class="lm-step" markdown>
<div class="lm-step__num">1</div>
<div markdown>
<div class="lm-kicker">30 min</div>
<div class="lm-title">Views — see</div>

Read [Views](apps/views.md) and save the WOW's view: `lm view apply -f docs/demo/wow-customer/clients-view.yaml`.

<p class="lm-check" markdown="span">**You know it worked when:** `lm view list` lists it and a user holding `scope:clients` renders it.</p>

```
$ lm view list
NAME          SCOPE    TITLE
clients-desk  clients  Clients
$ lm view clients-desk --filter city=Lyon
Clients · city=Lyon

Code client  Nom            Ville  Segment
C-200        Marie          Lyon   SILVER
C-500        Claire Martin  Lyon   
C-800        Albert Camus   Lyon   
C-900        Saint Exupery  Lyon   
```
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<div class="lm-kicker">30 min</div>
<div class="lm-title">Processes — react</div>

Read [Processes](apps/processes.md): declarative reactions to `data.row.changed`, always refusable, never writing to the source.

<p class="lm-check" markdown="span">**You know it landed when:** you can say what emits the event and what may refuse the action.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<div class="lm-kicker">45 min</div>
<div class="lm-title">Workflows — declare the lifecycle</div>

Read [Workflows](apps/workflows.md): a declared lifecycle over a status column, and a dormancy threshold that turns a document left unmoved into an alert.

<p class="lm-check" markdown="span">**You know it worked when:** you replayed [the WOW's step 9](wow.md#9-declare-what-a-status-means): `lm workflow apply` reports `EN LITIGE` as undeclared. **Pro:** scoped chat answers a lifecycle question from your declared graph.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">4</div>
<div markdown>
<div class="lm-kicker">20 min</div>
<div class="lm-title">Inbox — decide</div>

Read [Inbox](apps/inbox.md): where `decision.*` events (including `decision.dormant.<workflow>`) wait for a human, and `lm inbox ack` that closes them.

<p class="lm-check" markdown="span">**You know it worked when:** an acked decision leaves `lm inbox` and shows `[acked]` in `lm inbox --all`.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">5</div>
<div markdown>
<div class="lm-kicker">30 min</div>
<div class="lm-title">The PWA</div>

Read [The PWA](apps/pwa.md), then:

- open `https://<your-host>/app/` — cards on a phone, a table on a tablet;
- a phone cannot reach your laptop's `localhost`: put the [TLS proxy](deploy.md#one-https-origin-the-tls-proxy) in front first;
- choose each app's tabs with one line of its manifest: [`spec.tabs`](choosing-what-your-hub-offers.md#3-does-the-app-ask-for-it).

<p class="lm-check" markdown="span">**You know it worked when:** you see the same scope boundary on the phone and on the desktop.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">6</div>
<div markdown>
<div class="lm-kicker">40 min</div>
<div class="lm-title">Run it for real</div>

Read [Backup & restore](operations/backup-restore.md) and [Upgrade](operations/upgrade.md), and practise both before you need them.

<p class="lm-check" markdown="span">**You know it worked when:** `./deploy/selfhost/backup.sh` ends with `==> done: <folder>` and you know where that folder is.</p>
</div>
</article>
</div>

## If you get stuck

Common stumbles:

- **Wrong directory.** Commands assume the repository root. When a relative path fails,
  check where you are before checking anything else.
- **The first minute is not an outage.** Right after the stack starts, the hub can briefly
  answer `401` or refuse connections. Wait a minute and retry before diagnosing.
- **The ghost binary.** After rebuilding `lm`, run `lm version`. If it prints an old build,
  your PATH serves a stale copy.
- **Credentials.** Every password is generated at install time into `.env`, at the root of the clone.
  If a login page surprises you, the answer is in that file.
- **Ollama pulls one model at a time.** One `ollama pull <model>` per line, and make sure
  `ollama serve` isn't already running before starting it again.

If a step does not match what you see, report it — it is a documentation bug.

## The reference shelf

Everything not on the walk above, for when you need it.

**More source types** — same manifest grammar as CSV: [JDBC](connectors/jdbc.md) · [REST](connectors/rest.md) · [GraphQL](connectors/graphql.md) · [SOAP](connectors/soap.md) · [Kafka inbound](connectors/kafka.md) · [Transform at ingestion](connectors/transformers.md)

**Integrations & API** — [HTTP API for ask](rag/http-api.md) · [Admin API](admin-console-guide.md)

**Enterprise identity** — [Bring your own IdP](security/byo-idp.md) · [SCIM deprovisioning](security/scim.md)

**Day-2 operations** — [Observability](operations/observability.md) · [Rate limits](operations/rate-limits.md) · [Sizing & load tests](operations/sizing.md)

**Deploying at scale** — the cloud edition (commercial) ships a Kubernetes/Helm path; not part of this repository.
