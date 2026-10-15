# Choosing what your hub offers

> The same hub can show a warehouse team two tabs and a sales team four, run with the AI
> switched off for a client who forbids a model, or carry Search when it was built in.
> Four questions, asked in order, decide what a métier app shows — and each has one gesture.

| | |
|---|---|
| **Who** | the integrator installing or tuning a hub |
| **Gestures** | the image you deploy, one environment variable, one line in a `kind: App` manifest — and, for Recherche, a `kind: SearchIndex` |
| **See the result** | `lm module list`, then the app itself |
| **Edition** | both — what differs is which answers are possible ([Open vs Pro](editions.md)) |

## The four questions

Questions 1 to 3 decide whether a tab is on an app. Question 4 decides whether it is worth showing:
Recherche checks it before it appears, while Fiches opens anyway and says what is missing. Read them top to
bottom; the first *no* is your answer when something is missing.

```mermaid
flowchart LR
  a["1. Is it in the build?"] --> b["2. Is it switched on?"] --> c["3. Does the app ask for it?"] --> d["4. Is it ready to be used?"]
  d --> tab(["the tab is there"])
```

| # | Question | Who decides | Your gesture | Where you look |
|---|---|---|---|---|
| 1 | **Is it in the build?** | the image you deploy | pick Open or Pro; add a Search provider when you build the hub | `lm module list` |
| 2 | **Is it switched on?** | the operator | `LUMNIK_AI_ENABLED=false` turns the AI family off; absent means on | `lm module list` |
| 3 | **Does the app ask for it?** | the métier's `kind: App` | `spec.tabs: [...]`; leave it out to take everything the hub offers | `lm app apply` |
| 4 | **Is it ready to be used?** | the feature itself | for Recherche, declare a search index the user may read | the tab, and `lm search status` |

The tabs and who brings them:

| Tab | Shown as | Brought by | Needs |
|---|---|---|---|
| `fiches` | Fiches | every hub | a saved view for the app's scope (without one the tab opens and says so) |
| `inbox` | Décisions | every hub | — |
| `chat` | Chat | Pro (`rag` + `llm`) | the AI switched on |
| `search` | Recherche | a Search provider (`search_pg` or `search_opensearch`) | a search index the user may read |

## 1. Is it in the build?

What the image carries is fixed when the hub is built; no setting adds a module that is not there.
Ask the hub what it carries:

```
$ lm module list
NAME      VERSION  SCHEMA              STATUS  REASON
core      0.1.0    Platform            ACTIVE
identity  0.1.0    Identity & Tenancy  ACTIVE
llm       0.1.0    Model provider      ACTIVE
rag       0.1.0    Platform RAG        ACTIVE
```

That is a Pro hub: `llm` and `rag` are there, together. An Open hub lists `core` and `identity` only —
it has no Chat tab to offer, and nothing to switch on:

```
$ lm module list
NAME      VERSION  SCHEMA              STATUS  REASON
core      0.1.0    Platform            ACTIVE
identity  0.1.0    Identity & Tenancy  ACTIVE
```

Search is the one feature you add on top. The published image never carries a provider: you build the hub
with one (`deploy/selfhost/up.sh --search`, or `--search-cluster`, from the closed-edition sources — see
[choosing a provider](search/search-indexes.md#choosing-a-provider)). The provider then appears in the list:

```
$ lm module list
NAME       VERSION  SCHEMA              STATUS  REASON
core       0.1.0    Platform            ACTIVE
identity   0.1.0    Identity & Tenancy  ACTIVE
llm        0.1.0    Model provider      ACTIVE
rag        0.1.0    Platform RAG        ACTIVE
search_pg  0.1.0    PostgreSQL search   ACTIVE
```

To see which tabs each module brings, ask the platform door (integrator token, as in
[Which edition am I running?](editions.md#which-edition-am-i-running)); the hub offers the tabs of the
modules that are active:

```
$ curl -s "$LUMNIK_URL/api/platform/modules" -H "Authorization: Bearer $TOKEN" \
    | jq -c '.modules[] | select((.tabs|length)>0) | {id, active, tabs}'
{"id":"core","active":true,"tabs":["fiches","inbox"]}
{"id":"rag","active":true,"tabs":["chat"]}
{"id":"search_pg","active":true,"tabs":["search"]}
```

## 2. Is it switched on?

A module that is in the build can be switched off without rebuilding anything. One setting does it for
the whole AI family — the chat, the ask, the view generation and the embeddings:

```
LUMNIK_AI_ENABLED=false
```

Put it in the root `.env` (self-host) and recreate the hub with
`docker compose -f docker-compose.selfhost.yml up -d hub`; on Kubernetes, pass it to `deploy/helm/up.sh`
(chart value `ai.enabled`) and restart the hub pods (`kubectl rollout restart deployment/<release>-hub`).
A plain `docker compose restart` keeps the old environment. It must be exactly `true` or `false`; anything else stops the hub at startup with a message
naming the setting. The full list of what you see, and what stays running, is in
[Switching the AI off](rag/models.md#switching-the-ai-off).

The module list tells the difference between *switched off* and *not there*:

```
$ lm module list
NAME      VERSION  SCHEMA              STATUS    REASON
core      0.1.0    Platform            ACTIVE
identity  0.1.0    Identity & Tenancy  ACTIVE
llm       0.1.0    Model provider      INACTIVE  disabled:lumnik.ai.enabled
rag       0.1.0    Platform RAG        INACTIVE  disabled:lumnik.ai.enabled
```

- **Inactive** (Pro, switched off): the data is kept, the images are the same, and putting the setting back
  restores what you had. Content that needed indexing *while the AI was off* is not there when it returns —
  re-apply the workflow or re-ingest the file ([what comes back](rag/models.md#switching-the-ai-off)).
- **Absent** (Open): the module is not in the build, so it is not in the list at all.

Either way the Chat tab is gone from every app. Search is not part of this switch: turning the AI off leaves
Search exactly as it was.

## 3. Does the app ask for it?

Each métier app is a `kind: App` manifest. Without `tabs` it shows everything the hub offers (and
whatever a later release adds). To show fewer tabs, or to change their order, list them:

```yaml
kind: App
apiVersion: apps.lumnik.io/v1
metadata:
  name: stock
spec:
  title: Stock
  scope: stock
  icon: stock
  tabs: [fiches, inbox]        # no Chat for the warehouse
```

```
$ lm app apply -f stock.yaml
Applied app "stock" → scope stock
  tabs: fiches, inbox
```

Asking for a tab the hub does not offer is fine — the manifest stays valid on an Open hub and on a Pro
hub, and the tab shows up the day the hub has it. Here the same app on a hub that does not offer Chat (an Open hub, or the AI switched off), with
`tabs: [fiches, inbox, chat]`; the apply tells you, without failing:

```
$ lm app apply -f stock.yaml
Applied app "stock" → scope stock
  tabs: fiches, inbox, chat
! stock.yaml — 0 error(s), 1 warning(s)

  WARN     spec.tabs[2]
          tab 'chat' is not offered by this hub; the declaration is kept and takes effect when a module offering it is active
          → offered here: [fiches, inbox]
```

The last line is the hub's offer, spelled out — the quickest way to learn what an app can list. The rest of
the rules (order, the opening tab, what is refused) are in
[Choosing the tabs](apps/pwa.md#choosing-the-tabs).

Hiding a tab hides it, nothing more: who may read what is still decided by roles and scopes.

## 4. Is it ready to be used?

Having the tab on offer does not make its screen useful. Fiches needs a saved view for the app's scope;
Recherche needs a search index the user may read, and the PWA looks before showing it: with no index to
read, the tab is simply not there. Recherche's whole checklist is in
[Enabling Recherche for an app](apps/pwa.md#enabling-recherche-for-an-app).

To ask the hub whether Search itself is installed and healthy:

```
$ curl -s -H "Authorization: Bearer $TOKEN" "$LUMNIK_URL/api/search/capability"
{"state":"available","provider":"postgres","reason":null,"version":null}
```

On a hub with no provider the same question answers *not installed*, and a search is refused rather than
answered with an empty list:

```
{"state":"unavailable","provider":null,"reason":"not_installed","version":null}
```

## Recipes

**A warehouse app with no Chat, on a Pro hub.** Question 3 only: `tabs: [fiches, inbox]` in the app's
manifest, then `lm app apply -f`. The other apps keep their Chat.

**A client who forbids a model on their hub.** Question 2: `LUMNIK_AI_ENABLED=false`, then recreate the hub. Every
app loses its Chat tab at once, no manifest changes, and the rest of the hub (including Oracle, SCIM and
Search) is untouched.

**One métier searching, the others not.** Questions 3 and 4, per app: list `search` in that app's `tabs`,
put `scopes: [stock]` (the app's scope, here `stock`) in the `kind: Entity` manifests to search and re-apply them with `lm entity apply -f`, then declare a search index over them (`lm search apply -f`). The key is `scopes`, a list, on the entity; it is not the app's `scope`, and not the `scope:<tag>` role the users hold in Keycloak. For every *other* app, give it
a `tabs` list without `search` — an app that leaves `tabs` out takes everything the hub offers, Recherche
included, for any user who may read some index. An app whose scope has nothing indexed shows the tab
as long as its users can read an index of another scope, and answers *Aucun résultat*: the tab cannot tell, so
check the index when a métier expects hits. If they can read no index at all, the tab is not there.

## When a tab is missing

Go down the questions; stop at the first one that answers *no*.

| What you see | Look at | Likely answer |
|---|---|---|
| No Chat tab on any app | `lm module list` | `llm` and `rag` missing: this is an Open build. Listed `INACTIVE` with `disabled:lumnik.ai.enabled`: remove `LUMNIK_AI_ENABLED=false` and recreate the hub. |
| No Recherche tab on any app | `lm module list` | no `search_pg` / `search_opensearch`: the hub was built without a Search provider. |
| No Recherche tab, yet `search_pg` is `ACTIVE` | `lm search status` | no `kind: SearchIndex` is declared yet (the list is empty), or the users may read none of the entities the indexes cover. |
| Recherche missing on one app only | that app's manifest | `tabs` leaves out `search`; or its users hold scopes that read none of the indexed entities (compare each entity's `scopes` with their `scope:<tag>` roles). |
| Recherche there, but always *Aucun résultat* | `lm search status` | the index is not built yet (`STATUS`, `DOCUMENTS`), or no index covers the entities of the app's scope. |
| A tab listed in `tabs` does not show | `lm app apply -f` again | the hub does not offer it (question 1 or 2): the apply says so. The warning reflects the offer at the moment you apply, so apply again after switching the AI on or off; the declaration is kept and takes effect when the hub offers the tab. |
| Chat tab present, answers fail | `lm rag status` | the tab shows whenever `rag` and `llm` are active; a model that is not reachable is a different problem ([models](rag/models.md)). |

## See also

- [The two editions](editions.md) — what Open and Pro each carry
- [The métier PWA](apps/pwa.md) — the tabs, from the user's side
- [Switching the AI off](rag/models.md#switching-the-ai-off) — the full effect, and what it restores
- [Declare a search index](search/search-indexes.md) · [Query it](search/querying.md)
