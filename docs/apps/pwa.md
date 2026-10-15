# The métier PWA — your data in your pocket

The hub rendered on a phone. `/app/` is a small Preact app served by the hub itself —
no app store, no build step, no new endpoints: it speaks the same scope-bound API that
`lm` does, and installs from the browser like any PWA. One deployment carries every
métier; the `?m=` parameter decides whose app it is.

The UI speaks French — it is built for the métier user, not the integrator.

## Reaching it

```
https://<your-host>/app/            # the clients métier (default)
https://<your-host>/app/?m=stock    # the stock métier
```

Each métier in the registry carries its own title, home-screen icon, and web manifest
(`manifest-clients.json`, `manifest-stock.json`), swapped in before first paint — so
"Clients" and "Stock" install as two distinct apps on the same phone. An unknown or
absent `?m=` falls back to `clients`; it never errors.

What a métier user actually *sees* inside is decided elsewhere: by the saved views the
integrator has [applied](views.md) for that scope, and by the `scope:<tag>` role on the
user's account.

## Installing it on a phone

!!! note "At fleet scale: your MDM, not AirDrop"
    The gestures below are per-device — right for a pilot. For a managed fleet, push the
    same two artifacts through your MDM: the TLS façade's root CA as a trusted
    certificate, and the PWA URL as a managed web app / home-screen shortcut. The
    per-device steps then disappear entirely.

The PWA needs a **secure origin**: service workers and the real install prompt only
exist over `https`. The self-host stack ships a TLS façade for exactly this —
one Caddy origin fronting hub and Keycloak, with automatic Let's Encrypt for a public
`LUMNIK_TLS_HOST` (see `deploy/tls/README.md`). On a LAN address Caddy uses its
internal CA; trust its root on the device or installability is degraded (the
browser's rule, not ours).

Then, on the phone:

1. Open `https://<host>/app/?m=<métier>` and sign in.
2. **iOS Safari**: Share → *Add to Home Screen*. **Android Chrome**: the install
   prompt, or ⋮ → *Add to Home screen*.
3. The icon and name are the métier's own; the app opens standalone, no browser chrome.

## The tabs

The tabs an app shows are the ones its hub offers, narrowed by what the app declares ([Choosing the tabs](#choosing-the-tabs)). By default that is Fiches and Décisions everywhere, plus Chat on a build that carries it and Recherche on a hub that has [Search](../search/querying.md); a hub without them shows no such tab at all, not a greyed-out one. Same surfaces on every device — bottom tabs on a phone, a left rail on a display.

### Fiches — the saved views

The tab discovers its views from the hub (`GET /api/views?scope=<métier>`), then fetches the
chosen manifest by name (`GET /api/views/{name}`):

| Views applied for your métier | What you see |
|---|---|
| One | It opens directly. |
| Several | A picker to choose from. |
| **None** | An amber message naming the cause — ask your integrator to apply one (`POST /api/views/apply`, or `lm view apply -f`). |

Rendering and filtering go through the same `POST /api/views/render` as `lm view`, with the
same [refusal rules](views.md#refusal-rules). A view opens on its first **50 rows**, never the whole
table, and the screen says so: the header count reads `50+` and a line under the filters says *Première page —
d'autres résultats existent* (*Page 3 — d'autres résultats existent* further on). **Suivant ›** and **‹ Précédent** move one page at a time, **« Début** returns to the first page,
and the controls are disabled while a page is loading. The same controls sit **above and below** the rows, on the right, so a long page needs no scrolling to move on; the view's own title (its `spec.title`) heads the results. Changing a filter always goes back to the first
page; moving between pages keeps your filters. The last page says *Page N — dernière page* and its header
count is that page's own rows, without the `+`: the hub never counts the whole table, so "more results exist"
is the only total it states.

A phone shows the rows as cards, a tablet or a display as a table; only the one that fits your screen is
built, and it follows when you rotate or resize.

**Filters are built, not typed.** Pick a column by its label, then an operator that suits it, then a value
control that suits its type: a free text box, a number, a date or a date-and-time, or — when the column has a
declared set of values — a list to choose from. The hub decides which operators a column offers: a `money` or `time` column
is only ever offered *contient* and *=*. Each filter becomes a chip; tap it (or press Enter on it) to
remove it, and several chips combine with AND. The hub is still the one referee: a filter it refuses is
shown, as the hub worded it, under the filter bar, and a refused page move shows its refusal under the page
controls. In both cases the last good page stays on screen. A page link that has gone stale (a changed
filter, a record removed at the page edge) says *this page link is no longer valid — reload the view from
its first page*; **« Début** does exactly that.

Tapping a row opens the detail (`POST /api/views/detail`) — including the `_conflicts`
ledger: which source conflicted on which field, or which lookup key was ambiguous (a row with no value reads
"(empty)"), or an explicit "no ambiguity or divergence".

### Décisions — the inbox

The [inbox](inbox.md) fold (`GET /api/inbox`), newest first, with the ack gesture —
✓ *Traiter* posts `POST /api/inbox/{id}/ack`, the same idempotent ack-is-an-event as
`lm inbox ack`. The unacked count rides as a badge on the tab from the first frame.

!!! note "Tenant-wide, for now"
    Decisions are not yet bounded by métier — the tab shows the whole tenant's
    decisions and says so in its footer. Per-métier bounding is a known follow-up.

### Recherche — search the business data

A search box over your business data, the same search `lm search query` runs. Type a word or two,
press **Chercher**: the results are ranked, and the words that matched are highlighted in each one.
Nothing is searched while you type; one press, one search.
Each result is headed by the index's [business title](../search/search-indexes.md#choosing-a-title) when the integrator declared one, otherwise by the entity name.
A field the index [links to your ERP](../search/search-indexes.md#linking-a-result-to-your-erp) shows its value as a link that opens the other system in a new tab; the rest of the card still opens the record.
Fields carry the [human names](../search/search-indexes.md#human-names-for-fields) their entity gives them, on the results, the record and the filter chips; a column without a label keeps its name.
Once there is something on screen, a **✕** next to the box puts the screen back to empty — query, filters, results and any open record — without searching.

- **Who sees the tab.** Recherche appears when your hub has Search installed and you may read
  at least one search index ([declare one](../search/search-indexes.md)). A hub without Search, or a user with no
  index to read, simply has no Recherche tab. If Search is installed but struggling, the tab is there and a
  line at the top says so with the reason; if the app could not find out (the network dropped at start-up), it keeps the tab; a search that fails says why too, and how long to wait when the
  hub told it.
- **What it searches.** Only your app's métier. The `stock` app searches the entities tagged `stock`, even
  for someone who also holds other scopes; the app never searches wider than its own scope, and what you may
  read is still decided by your roles and scopes.
- **Narrowing.** The values the hub counts for a search (a city, a segment) appear as chips under the box. Tap one to
  keep only those results, tap it again to lift it. **Plus de résultats** loads the next page.
- **Opening a result.** Tap a result to open its record, read-only, with each source's value shown where
  a source disagrees with the others. If the record has changed since the search, the screen says it can no longer be found; if
  several records answer to the same key, it says so rather than choosing one.
- **No result** reads *Aucun résultat*. It means nothing matched: the screen does not claim to know whether your
  métier has anything indexed at all. If you expect results and see none, ask your integrator to check that
  an index exists for your métier ([Enabling Recherche for an app](#enabling-recherche-for-an-app)).
- **How fresh it is.** A line under the chips says how up to date the search index is.

### Chat — ask your data

The guarded analytic ask, through the `lm_user` door (`POST /api/rag/chat`, analytic
mode, bound to the métier's scope). An answer always shows the SQL it ran and the row
count, folded under the reply. An answer that ran **no** SQL is an abstention and wears
the amber refusal tone — the same [honesty contract](../transparency/ask-honesty.md)
as everywhere else. The thread survives tab switches but not a reload; nothing is
stored.

When the answer filtered on a value a source disagrees about, an amber block
« Désaccord sur une valeur filtrée » sits under the freshness line and above the SQL. One
entry per record: *P-1008 · Ville — comptée avec « Lyon »*, or *écartée, alors qu'une source
indique « Villeurbanne »*, followed by each source's value; « … et d'autres fiches » closes it
when more than ten exist. An answered result then also carries the button « Ouvrir les fiches — les désaccords sont sur
chaque fiche », shown whenever `disputed` is non-empty (the door `conflicts` is present;
before, only a refusal carried doors) and the app shows the Fiches tab — an app that hides it has nowhere to send the button. Nothing renders when nothing is disputed — and the absence is not
a guarantee: the [shapes the disclosure skips](../transparency/ask-honesty.md#disputed-rows-a-disclosure-not-a-veto)
(`OR`, joins, ranges…) show no block.

## Responsive modes

One codebase, three postures — CSS breakpoints, not separate apps:

| Width | Fiches | Navigation | Detail |
|---|---|---|---|
| phone (< 640px) | cards | bottom tabs | bottom sheet |
| tablet (≥ 640px) | table | bottom tabs | bottom sheet |
| display (≥ 900px) | table | left rail | pinned side panel |

## Identity & scope

The PWA is an OIDC **public client**: `lumnik-spa`, authorization code + PKCE (S256)
against the realm. Tokens live in `sessionStorage` for the tab's lifetime; a silent
refresh renews them ~60 s before expiry; logout is RP-initiated (Keycloak session
ends too, not just the local one). Under the TLS façade, Keycloak is reached
same-origin at `/realms/lumnik` — one issuer for every device.

!!! warning "External IdP: `lumnik-spa` needs an audience mapper"
    The hub requires `aud=lumnik-backend` on every token. The bundled realm carries
    the mapper for `lumnik-spa`; if you broker or replace Keycloak with your own IdP,
    the SPA client must add the same audience mapper — without it, every PKCE token
    is a plain `401` at the hub, with nothing wrong visible on the login side.
    (Learned live: see `docs/studies/2026-07-11-mobile-metier-renderer-study.md`.)

Access is the métier-scope boundary, nothing PWA-specific: the user needs the
`scope:<tag>` role for their métier. Views, entities, and chat all refuse uniformly —
`403 {"error":"scope not granted"}` — and the PWA renders that as the amber
refusal, never a broken screen.

## Offline behavior

The service worker caches the **app shell only** — HTML, CSS, modules, icons. Data is
**never** cached: everything under `/api/*` and `/realms/*` bypasses the worker
entirely and always hits the network. Tenant data and tokens never touch disk.

So offline, the app opens instantly — and says the truth: an "Hors ligne" screen with
a retry button, instead of stale rows pretending to be fresh. When the network returns,
so does the data.

!!! note "Deploying a shell change"
    The browser only re-installs the shell when `sw.js` itself changes bytes. Every
    shell-file change must bump the `CACHE` version in `app/sw.js` — otherwise
    returning visitors keep the old shell forever.

## How old is what I'm looking at?

lumnik is a scheduled mirror, not a live feed: a change in the source appears at the
**next run that reads it** (its cron, or a manual `lm connector run`) — deletes never
propagate, and a jdbc watermark run only re-reads rows whose watermark advanced. Rows in
`connector.t_*` tables carry an `_ingested_at` timestamp (REST hybrid tables carry
`indexed_at`), but treat it with care: under the default conflict mode an unchanged
re-ingested row keeps its *first* timestamp. The reliable signal is the **run history**,
and chat answers now carry it: every answered ask includes a `data as of` timestamp —
the scope's last completed run (`lm ask` prints it; the API field is `dataAsOf`).
The PWA shows the same dial: the chat answer and the Fiches list both carry a quiet
« données : il y a … » line — absent when the scope has no completed run, never a
fabricated age. For the schedule itself, your integrator reads the run history in
one command (`lm run list`, or the TUI's `:runs`).

## How to…

### Add a métier

A métier app's identity — its title, the scope it reads through, its icon and its
accent/background colours — is something you *declare*, in a `kind: App` manifest
applied with `lm app apply -f`. The hub validates and stores it; the PWA reads its
whole registry live from `GET /api/apps` on every login. A métier is an entry in that
registry, not a deployment — no hub rebuild, no PWA source change.

```yaml
kind: App
apiVersion: apps.lumnik.io/v1
metadata:
  name: lumilait
spec:
  title: Lumilait
  scope: lumilait
  icon: generic
  theme:
    accentColor: "#4fd1a5"
    backgroundColor: "#f4f1ea"
  default: false
```

- **`icon`** — one of a curated set: `clients`, `stock`, `generic`, `event`, `alert`,
  `contract`. There is no upload path for a custom icon; `generic` is the right choice
  for a métier with no dedicated one.
- **`theme`** — both colours are optional, defaulting to today's shared
  `#8b7ff0`/`#14121f`. `accentColor` is contrast-checked (WCAG AA) because it doubles
  as the chat bubble's background, not only its text colour.
- **`default`** — the app an absent `?m=` resolves to. At most one manifest may hold
  it; the apply door refuses to demote the current default without another manifest
  promoting a replacement in the same apply.
- **`tabs`** — optional: which tabs the app shows, and in what order. Absent means every
  tab the hub offers. See [Choosing the tabs](#choosing-the-tabs).

1. **Declare it** — `lm app apply -f app.yaml`.
2. **Server side** — apply at least one saved view for the scope
   (`POST /api/views/apply`) and grant the users their `scope:<tag>` role in Keycloak.

Without step 2 the app still opens — Fiches states that no view is applied, and
every scoped call refuses with the uniform 403.

#### See what is declared

`lm app list` shows the registry the PWA reads, so you can check an apply, or find the app a login resolves to:

```
$ lm app list
NAME            TITLE      SCOPE      ICON     ACCENT   BACKGROUND  DEFAULT  TABS
pwa-pagination  Catalogue  catalogue  stock    #8b7ff0  #14121f     yes      fiches,search
stock           Stock      stock      generic  #f5b83d  #14121f              (all)
```

`DEFAULT` marks the app an absent `?m=` opens; `(all)` under `TABS` means the app declares none and shows every tab
the hub offers. For a script, `-o json` (or `-o yaml`) gives every field the hub sends for each app, theme included:

```
$ lm app list -o json | jq -e '.[] | select(.name == "pwa-pagination") | .accentColor == "#8b7ff0" and .backgroundColor == "#14121f"'
true
```

The list is scope-bound like the PWA's own: an account holding `scope:` roles sees the apps of its scopes. If the
hub refuses the call (an expired login, a missing role), that refusal is the error and the exit code is non-zero —
never an empty list. A word that is not a subcommand (`lm app foo`) is an error too.

### Choosing the tabs

This page covers the app's own list. What a hub offers in the first place — the build, the AI switch, a Search provider — is in [Choosing what your hub offers](../choosing-what-your-hub-offers.md).

An app shows every tab its hub has: Fiches and Décisions everywhere, plus Chat when your edition
includes it and Recherche when your hub has Search. To show fewer — no Chat for the warehouse, say — or to change the order, list the tabs
in the app's manifest, then apply it with `lm app apply -f app.yaml`:

```yaml
spec:
  tabs: [fiches, inbox]
```

- Leave `tabs` out and the app shows every tab the hub has and the app can draw, including one added by a later release.
- The app opens on the first tab of your list that the hub has.
- The names are `fiches`, `inbox` (shown as *Décisions*), `chat` and `search` (shown as *Recherche*).
- Asking for a tab your hub does not have is fine: `lm app apply` tells you, and the tab appears the
  day the hub has it. The same manifest therefore works on an Open hub and on a Pro hub.
- If none of the tabs you listed exists on the hub, the app says that no feature is available for it,
  instead of showing an empty screen.
- Hiding a tab does not lock anything: who may read what is still decided by roles and scopes.
- `lm app apply` refuses a value that is not a list, an empty list (leave `tabs` out instead), a tab
  named twice, more than 16 tabs, and a name that is not a lowercase word (letters, digits and
  dashes, starting with a letter).

### Enabling Recherche for an app

Recherche shows up for a métier once four things are in place:

1. **A Search provider in the hub** — the hub is built with one ([choosing a provider](../search/search-indexes.md#choosing-a-provider)).
2. **Entities carrying the app's scope** — the entities you want searched are tagged with the same scope as the
   app's `spec.scope`. The app searches those and no others.
3. **A search index over them** — apply a `kind: SearchIndex` ([declare one](../search/search-indexes.md)) with
   `lm search apply -f index.yaml`. The hub builds it within about a minute; `lm search rebuild NAME --wait`
   builds it now and waits.
4. **The tab in the app's list** — if the app's manifest has no `tabs`, Recherche is there on its own. If it lists
   its tabs, add `search`:

```yaml
spec:
  tabs: [fiches, inbox, search]
```

Check it from the terminal with `lm search status` (every index) or `lm search status NAME` (one): an index
that is built and fresh is what the métier's search runs over. A user must also hold the roles that let them
read those entities; the tab does not widen anyone's access.

## See also

- [kind: View](views.md) — the manifests the Fiches tab renders.
- [Inbox](inbox.md) — the decisions fold behind the Décisions tab.
- [Scopes](../rag/scopes.md) — the boundary the whole app lives within.
