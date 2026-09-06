# Parcours — the guided path

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Guided onboarding</p>
<p class="lm-lead" markdown="span">The documentation is a map, organized by system. This page is the **walk**: the same pages, in the order a newcomer should live them. Follow it top to bottom. You go from an empty machine to a running hub, a fused entity, a métier chat that cites its sources, and a workflow that flags what stopped moving.</p>
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

Docker, Go 1.25+ (to build `lm` — the floor is `lm/go.mod`), and [Ollama](https://ollama.com) — the parcours' chat and ask steps need it. The exhaustive list — sizes, traps, the 60-second self-check — is [Prerequisites](quickstart/prerequisites.md).
</div>
</div>
</section>

<div class="lm-pane lm-pane--accent" markdown>
<div class="lm-kicker">The one rule</div>

Run every command **from the repository root** unless a page says otherwise. Half of all mysterious failures are a wrong working directory.

You do not have that repository yet — step 2 below is where you get it. If you want it now:

```bash
git clone https://github.com/icreated/lumnik-open.git && cd lumnik-open
```
</div>

## The three half-days at a glance

<div class="lm-grid" markdown>
<article class="lm-card" markdown>
<div class="lm-kicker">Half-day 1</div>
<h3>Defrost reading</h3>

Deploy the hub, meet `lm`, run the WOW, and prove that ingestion stayed read-only and lost nothing.
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Half-day 2</div>
<h3>Defrost reasoning</h3>

Understand entities, honesty guards, scopes, models, and the TUI an integrator operates from.
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Half-day 3</div>
<h3>Defrost time</h3>

Move from a mirror of the source to views, processes, workflows, inbox decisions, and the métier PWA.
</article>
</div>

## Half-day 1 — defrost *reading*

Goal of the day: legacy data flows into the hub, read-only, and you can prove nothing was lost.

<div class="lm-rail" markdown>
<article class="lm-step" markdown>
<div class="lm-step__num">1</div>
<div markdown>
<h3>The pitch — 10 min</h3>

Read the [Home page](index.md). It tells you what lumnik is (a read-only defrost of legacy systems) and, just as important, what it is not (not a migration, not a BI project).

<p class="lm-check" markdown="span">**You know it landed when:** you can explain why lumnik is neither a migration nor a BI project.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<h3>Deploy the hub — 30 min</h3>

Follow [Deploy lumnik](deploy.md), self-host path: one command, `deploy/selfhost/up.sh`. It generates all secrets into a local `.env` — your credentials live there, including `HUBADMIN_PASSWORD`.

<p class="lm-check" markdown="span">**You know it worked when:** the script reports the stack healthy and prints where the hub answers.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<h3>Meet <code>lm</code> — 20 min</h3>

The [60-second Quickstart](quickstart/installer.md) builds the `lm` binary, points it at your hub, and logs you in — then puts a first file into the hub with one command.

<p class="lm-check" markdown="span">**You know it worked when:** `lm login` succeeds and `lm connector list` answers — an authenticated round trip to your own hub.</p>

```
$ lm connector list
NAME       TYPE      TENANT  SCHED  STATUS     LAST RUN
customers  csv-file  1              Completed  2026-09-05 05:38
```
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">4</div>
<div markdown>
<h3>The WOW — 60–90 min</h3>

The heart of the first half-day. Read [The WOW, step by step](wow.md), then replay it yourself with [The WOW, hands-on](demo/wow-customer/README.md): a SOAP Siebel and a CSV fused into one canonical Customer, asked in natural language.

<p class="lm-check" markdown="span">**You know it worked when:** you ask a question in French and the answer cites its sources.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">5</div>
<div markdown>
<h3>How a connector is built — 30 min</h3>

Now that you have seen one run, read [Connectors overview](connectors/index.md) and the manifest you just used, [CSV & files](connectors/csv.md). Every other source type follows the same grammar — the full list is on the shelf below.

<p class="lm-check" markdown="span">**You know it landed when:** you can explain the relationship between manifest, endpoint, and ingestion report.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">6</div>
<div markdown>
<h3>What was ingested, skipped, quarantined — 20 min</h3>

Read [Seeing what was ingested, skipped, and quarantined](transparency/ingestion-honesty.md).

<p class="lm-check" markdown="span">**You know it worked when:** on your own run, `lm run get` shows the arithmetic holding — IN = OUT + SKIPPED — and DEAD-LETTERS never exceeds SKIPPED: every row you read is accounted for, dropped or not.</p>

```
$ lm run get f0466804
ID        CONNECTOR  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
f0466804  orders     Completed  10  8    2        2             2026-09-05 05:38  2026-09-05 05:38
⚠ 2 rows quarantined — see: lm dlq list --run f0466804-0824-4eb4-8aa2-54e263dccafe
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
<h3>Fuse sources into one truth — 40 min</h3>

Read [Entities — fuse](entities.md) and inspect the Customer entity you applied during the WOW.

<p class="lm-check" markdown="span">**You know it worked when:** you can find the `_conflicts` ledger and explain why a row is in it.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<h3>The five guards — 30 min</h3>

Read [Ask honesty — the five guards](transparency/ask-honesty.md). This page is lumnik's spine: an abstention is a feature, a confident wrong answer is the cardinal sin.

<p class="lm-check" markdown="span">**You know it worked when:** you can name the guard that stops a hallucinated table name.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<h3>Scopes — 30 min</h3>

Read [Scopes](rag/scopes.md): a scope is declared on the connector (`metadata.scopes`) and granted as a Keycloak role. Zero scope roles = tenant-wide; one or more = bound.

<p class="lm-check" markdown="span">**You know it worked when:** a scope-bound user gets `403 scope not granted` outside their métier — and sees answers inside it.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">4</div>
<div markdown>
<h3>Models & providers — 20 min</h3>

Read [Models & providers](rag/models.md) to know which LLM answers you, and how to swap it (Ollama local or a remote provider).

<p class="lm-check" markdown="span">**You know it worked when:** you know whether your install answers from Ollama or a remote provider.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">5</div>
<div markdown>
<h3>The integrator's cockpit — 40 min</h3>

Skim the [CLI & TUI reference](cli/lm-reference.md), then open the TUI with `lm ui` and redo yesterday's WOW checks without leaving it.

<p class="lm-check" markdown="span">**You know it worked when:** you can find any connector run and its report using only the TUI.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">6</div>
<div markdown>
<h3>Who may do what — 20 min</h3>

Read [Roles & permissions](security/role-management.md) and skim [RLS & database roles](security/rls.md): tenant isolation is enforced by the database itself, not by application code being careful.

<p class="lm-check" markdown="span">**You know it landed when:** you can explain why isolation survives even a buggy query.</p>
</div>
</article>
</div>

## Half-day 3 — defrost *time* and the apps

Goal of the day: the hub stops being a mirror and starts finding things — and each métier gets a surface.

<div class="lm-rail" markdown>
<article class="lm-step" markdown>
<div class="lm-step__num">1</div>
<div markdown>
<h3>Views — see — 30 min</h3>

Read [Views](apps/views.md) and apply the WOW's saved view.

<p class="lm-check" markdown="span">**You know it worked when:** `GET /api/views` lists it and a scoped user can render it.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<h3>Processes — react — 30 min</h3>

Read [Processes](apps/processes.md): declarative reactions to `data.row.changed`, always refusable, never writing to the source.

<p class="lm-check" markdown="span">**You know it landed when:** you can say what emits the event and what may refuse the action.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<h3>Workflows — declare the lifecycle — 45 min</h3>

Read [Workflows](apps/workflows.md). This is the third defrost axis: the source shows current state; lumnik, ingesting repeatedly, reconstructs *time* — and a declared lifecycle plus a dormancy threshold turns silence into an alert.

<p class="lm-check" markdown="span">**You know it worked when:** the métier chat answers a lifecycle question from your declared graph, not from imagination.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">4</div>
<div markdown>
<h3>Inbox — decide — 20 min</h3>

Read [Inbox](apps/inbox.md): where `decision.*` events (including `decision.dormant.<workflow>`) wait for a human, and the ack gesture that closes the loop.

<p class="lm-check" markdown="span">**You know it landed when:** you can distinguish the emitted event from the human acknowledgement.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">5</div>
<div markdown>
<h3>The métier PWA — 30 min</h3>

Read [The métier PWA](apps/pwa.md) and open `https://<your-host>/app/` on a phone: cards on a phone, a table on a tablet, the same scope rules as everywhere else. A phone cannot reach your laptop's `localhost` — the TLS façade on the [deploy page](deploy.md) is what gives both devices one host and one token issuer.

<p class="lm-check" markdown="span">**You know it worked when:** you see the same scope boundary on the phone and on the desktop.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">6</div>
<div markdown>
<h3>Run it for real — 40 min</h3>

Read [Backup & restore](operations/backup-restore.md) and [Upgrade](operations/upgrade.md) — the two pages you must have practiced *before* the day you need them.

<p class="lm-check" markdown="span">**You know it worked when:** you have run one backup and know where it landed.</p>
</div>
</article>
</div>

## If you get stuck

Lessons from the first strangers who walked this path — each of these cost someone an hour:

- **Wrong directory.** Commands assume the repository root. When a relative path fails,
  check where you are before checking anything else.
- **The first minute is not an outage.** Right after the stack starts, the hub can briefly
  answer `401` or refuse connections. Wait a minute and retry before diagnosing.
- **The ghost binary.** After rebuilding `lm`, run `lm version`. If it prints an old build,
  your PATH serves a stale copy — prefer a symlink to your build over `cp`.
- **Credentials.** Everything is generated at install time into the `.env` next to `up.sh`.
  If a login page surprises you, the answer is in that file.
- **Ollama pulls one model at a time.** One `ollama pull <model>` per line, and make sure
  `ollama serve` isn't already running before starting it again.

If a step of this parcours doesn't match what you experience, that gap is a bug in the
documentation — please report it. The stumbles of the first walkers are folded back into this page.

## The reference shelf

Everything not on the walk above, so nothing in the documentation is orphaned. Come back
here when the need arises — not before.

**More source types** — same manifest grammar as CSV: [JDBC](connectors/jdbc.md) · [REST](connectors/rest.md) · [GraphQL](connectors/graphql.md) and [SOAP](connectors/soap.md) — request shapes on the REST engine · [Kafka inbound](connectors/kafka.md) · [Transform at ingestion](connectors/transformers.md)

**Integrations & API** — [HTTP API for ask](rag/http-api.md) · [Admin API](admin-console-guide.md)

**Enterprise identity** — [Bring your own IdP](security/byo-idp.md) · [SCIM deprovisioning](security/scim.md)

**Day-2 operations** — [Observability](operations/observability.md) · [Rate limits](operations/rate-limits.md) · [Sizing & load tests](operations/sizing.md)

**Deploying at scale** — the cloud edition (commercial) ships a Kubernetes/Helm path; not part of this repository.
