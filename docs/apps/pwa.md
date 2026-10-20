# The métier PWA — your data in your pocket

The hub rendered on a phone. `/app/` is served by the hub itself — no app store, no build
step — and installs from the browser like any PWA. One deployment carries every métier; the
`?m=` parameter decides whose app it is.

The UI speaks French — it is built for the métier user, not the integrator.

## Reaching it

```
https://<your-host>/app/            # the app declared default: true
https://<your-host>/app/?m=stock    # the stock métier
```

After sign-in the PWA looks `?m=` up in the apps the user may see ([`lm app list`](#see-what-is-declared)):

| `?m=` | Opens |
|---|---|
| a name in the list | that app, with its own title, icon and theme |
| absent | the app declared `default: true` the user sees — or their only app; otherwise the refusal below |
| unknown, or an app of a scope the user does not hold | *Application inconnue — vérifie le lien ou contacte ton intégrateur.* |

Each app installs as its own home-screen icon: "Clients" and "Stock" are two apps on the same phone.

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
internal CA; trust its root on the device, or the browser will not offer a real install.

Then, on the phone:

1. Open `https://<host>/app/?m=<métier>` and sign in.
2. **iOS Safari**: Share → *Add to Home Screen*. **Android Chrome**: the install
   prompt, or ⋮ → *Add to Home screen*.
3. The icon and name are the métier's own; the app opens standalone, no browser chrome.

## The tabs

The tabs an app shows are the ones its hub offers, narrowed by what the app declares ([Choosing the tabs](#choosing-the-tabs)):

| Tab | Shown |
|---|---|
| Fiches | always |
| Décisions | always |
| Chat | on a build with the AI, switched on |
| Recherche | on a hub with [Search](../search/querying.md), for a user who may read an index |

A tab the hub does not offer is absent, not greyed out. Bottom tabs on a phone, a left rail on a display.

### Fiches — the saved views

The tab lists the [saved views](views.md#the-saved-view-store) of the app's scope:

| Views applied for your métier | What you see |
|---|---|
| One | It opens directly. |
| Several | A picker to choose from. |
| **None** | An amber message naming the cause — ask your integrator to apply one (`lm view apply -f`). |

The [refusal rules](views.md#refusal-rules) are those of `lm view`. A view opens on its first
**50 rows**, under its `spec.title`:

| On screen | Means |
|---|---|
| header count `50+`, *Première page — d'autres résultats existent* | more rows exist; no total is counted |
| **Suivant ›** / **‹ Précédent** | one page forward / back (above and below the rows) |
| **« Début** | back to the first page |
| *Page N — dernière page*, count without `+` | the last page |
| *this page link is no longer valid — reload the view from its first page* | the data or the filter changed under the page; tap **« Début** |

Changing a filter goes back to the first page; moving between pages keeps the filters. A phone
shows cards, a tablet or a display a table.

**Filters are built, not typed:**

- pick a column by its label, then an operator, then a value — a text box, a number, a date, or a
  list when the column declares its values;
- a `money` or `time` column offers only *contient* and *=*;
- each filter is a chip; tap it (or Enter) to remove it; chips combine with AND;
- a refused filter or page move shows the hub's message, and the last good page stays on screen.

Tapping a row opens the detail, ending with the sources' disagreements (`_conflicts`) or
*aucune ambiguïté ni divergence*.

### Décisions — the inbox

The [inbox](inbox.md), newest first: open decisions with a ✓ *Traiter* button (the same ack as
`lm inbox ack`), handled ones dimmed with *✓ traité*. The open count is a badge on the tab.

It shows the decisions of the métiers the user holds — every decision of the tenant for a user
with no `scope:` role ([who sees which decision](inbox.md#who-sees-which-decision)). It is not
narrowed to the open app: a user holding `desk` and `stock` sees both in either app, at most the
50 newest, handled ones included.

### Recherche — search the business data

A search box over your business data — the search `lm search query` runs. Type a word or two
and press **Chercher**: ranked results, matched words highlighted. Nothing is searched while you type.

- Each result is headed by the index's [business title](../search/search-indexes.md#choosing-a-title), or the entity name.
- A field the index [links to your ERP](../search/search-indexes.md#linking-a-result-to-your-erp) opens the other system in a new tab.
- Fields carry their entity's [human names](../search/search-indexes.md#human-names-for-fields).
- **✕** next to the box empties the screen — query, filters, results and open record.

| Situation | The tab |
|---|---|
| hub without Search, or a user with no index to read | absent |
| Search installed but struggling | present, with a line saying why |
| the app could not find out at start-up (network) | present |
| a search fails | says why, and how long to wait when the hub says so |

- **What it searches.** Only the app's métier: the `stock` app searches the entities tagged
  `stock`, even for a user holding other scopes too.
- **Narrowing.** Counted values (a city, a segment) appear as chips under the box; tap one to keep
  only those results, again to lift it. **Plus de résultats** loads the next page.
- **Opening a result.** The record opens read-only, with each source's value where they disagree.
  A record changed since the search, or a key shared by several records, is said so.
- **No result** reads *Aucun résultat*: nothing matched. Expected results? Check that an index
  exists for the métier ([Enabling Recherche for an app](#enabling-recherche-for-an-app)).
- **How fresh it is.** A line under the chips gives the index's age.

### Chat — ask your data

The guarded analytic ask (as `lm ask`), bound to the app's scope. An answer shows the SQL it
ran and the row count, folded under the reply. An answer that ran **no** SQL is an abstention,
in the amber refusal tone — the [ask contract](../transparency/ask-honesty.md). The thread survives
tab switches, not a reload; nothing is stored.

When the answer filtered on a value a source disagrees about:

- an amber block « Désaccord sur une valeur filtrée » sits above the SQL, one entry per record —
  *P-1008 · Ville — comptée avec « Lyon »*, or *écartée, alors qu'une source indique
  « Villeurbanne »* — closed by « … et d'autres fiches » past ten;
- the button « Ouvrir les fiches — les désaccords sont sur chaque fiche » opens them, when the app
  shows the Fiches tab;
- no block is not a guarantee: some query [shapes are skipped](../transparency/ask-honesty.md#disputed-rows-a-disclosure-not-a-veto)
  (`OR`, joins, ranges…).

#### Follow-up questions

The chat speaks as **lumnik**: the input reads « Demande à lumnik… », and a question may start with « lumnik, … ».
A question can continue the previous one — « Et HAWARD ? » after a question about Badu, « Et seulement celles de la
catégorie Pompes ? » to narrow it. On a phone, over a catalogue of eight articles (bubbles marked by who wrote them,
the SQL folded):

```text
[you]     Donne-moi les références dont la description contient Badu.
[lumnik]  Il y a 3 références dont la description contient "Badu". Les références sont : 2195.300.037, 2195.300.038 et 3000.000.001.
          ▶ le SQL exécuté · 3 lignes
[you]     Et HAWARD ?
[lumnik]  Il y a 2 références dont la description contient HAWARD :

          1. 4100.000.001
          2. 4100.000.002

          Total de références : 2
          Compris comme : Donne-moi les références dont la description contient HAWARD.
          ▶ le SQL exécuté · 2 lignes
[you]     Et seulement celles de la catégorie Pompes ?
[lumnik]  Les références de la catégorie Pompes dont la description contient HAWARD sont : 4100.000.001.
          Compris comme : Donne-moi les références de la catégorie Pompes dont la description contient HAWARD.
          ▶ le SQL exécuté · 1 ligne
[you]     Et le deuxième, il coûte combien ?
[lumnik]  Je n'ai pas pu rattacher cette question à la précédente. Posez-la en entier, avec la valeur ou le nom auquel elle renvoie.
          [ suite de : Donne-moi les références de la catégorie Pompes dont la description contient HAWARD. ✕ ]
          Nouvelle conversation
```

- **« Compris comme : … »** sits under an answer whose question lumnik completed: it is the question that was really
  answered, and the SQL is that question's. Read it before trusting the figure. A question asked on its own gets no
  such line.
- **The chip « suite de : … »**, just above the input, is the one sentence the next question continues: the last
  question lumnik answered, as it understood it. A refusal or an error does not replace it, so you can rephrase and try
  again. A long sentence loses its middle on the chip, never its end. Tap the chip to ask the next question on its own.
- **« Nouvelle conversation »** clears the thread and the chip.
- **What lumnik remembers is that sentence, nothing else** — not the answers, not the questions before it. « Le
  deuxième », « celui-ci » point at a row of an answer lumnik does not keep, so they are refused, and the refusal asks
  for the value. A reload forgets the sentence, like the whole thread; and each app opens on its own page, so a thread
  never carries from one métier to another.

How a follow-up is resolved, and when it is refused, is in
[A question that follows the previous one](../transparency/ask-honesty.md#a-question-that-follows-the-previous-one);
the field the PWA sends is [`previous`](../rag/http-api.md#follow-up-questions).

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

Access is the métier-scope boundary, nothing PWA-specific: the user needs the
`scope:<tag>` role for their métier. Views, entities, and chat all refuse uniformly —
`403 {"error":"scope not granted"}` — and the PWA renders that as the amber
refusal, never a broken screen.

## Offline behavior

Only the **app shell** (HTML, CSS, modules, icons) is cached on the device. Data and tokens
never are: every data call goes to the network.

Offline, the app opens and shows *Hors ligne — tes données reviendront avec le réseau.* with a
**Réessayer** button — never stale rows.

## How old is what I'm looking at?

A change in the source appears at the **next run that reads it** (its schedule, or
`lm connector run`). The Fiches list and every chat answer carry a « données : il y a … » line:
the age of the scope's last completed run. It is absent when the scope has none.

The integrator reads the run history with `lm run list` (or the TUI's `:runs`); what a run
skipped or quarantined is in [ingestion transparency](../transparency/ingestion-honesty.md).

## How to…

### Add a métier

A métier app — title, scope, icon, colours — is a `kind: App` manifest applied with
`lm app apply -f`. The PWA reads the apps at every login: no rebuild, no redeploy.

```yaml
--8<-- "docs/apps/demo/apps/10-lumilait.yaml"
```

- **`icon`** — one of a curated set: `clients`, `stock`, `generic`, `event`, `alert`,
  `contract`. There is no upload path for a custom icon; `generic` is the right choice
  for a métier with no dedicated one.
- **`theme`** — both colours are optional, defaulting to `#8b7ff0`/`#14121f`. `accentColor`
  is contrast-checked (WCAG AA): it is also the chat bubble's background.
- **`default`** — the app an absent `?m=` opens. At most one app holds it. To move it, apply
  another app with `default: true`; re-applying the current default with `default: false` is
  refused. Here `clients-nodefault.yaml` is the seeded `clients` app with `default: false`:

    ```yaml
    kind: App
    apiVersion: apps.lumnik.io/v1
    metadata:
      name: clients
    spec:
      title: Clients
      scope: clients
      icon: clients
      default: false
    ```

    ```
    $ lm app apply -f clients-nodefault.yaml
    Error: API error 400: cannot remove the last default app — apply a different app with default: true first, which demotes this one automatically
    ```

- **`tabs`** — optional: which tabs the app shows, and in what order. Absent means every
  tab the hub offers. See [Choosing the tabs](#choosing-the-tabs).

```
$ lm app apply -f docs/apps/demo/apps/10-lumilait.yaml
Applied app "lumilait" → scope lumilait
```

Then apply at least one saved view for the scope (`lm view apply -f`) and grant the users their
`scope:<tag>` role in Keycloak. Without them the app still opens: Fiches says that no view is
applied, and every scoped call is refused.

#### See what is declared

`lm app list` shows the registry the PWA reads, so you can check an apply, or find the app a login resolves to:

```
$ lm app list
NAME      TITLE     SCOPE     ICON     ACCENT   BACKGROUND  DEFAULT  TABS
clients   Clients   clients   clients  #8b7ff0  #14121f     yes      (all)
lumilait  Lumilait  lumilait  generic  #4fd1a5  #f4f1ea              (all)
stock     Stock     stock     stock    #8b7ff0  #14121f              (all)
```

A new hub carries `clients` (the default) and `stock`. `DEFAULT` marks the app an absent `?m=`
opens; `(all)` means the app declares no `tabs`. For a script, `-o json` (or `-o yaml`) gives
every field, theme included:

```
$ lm app list -o json | jq -e '.[] | select(.name == "lumilait") | .accentColor == "#4fd1a5" and .backgroundColor == "#f4f1ea"'
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
- Asking for a tab your hub does not have is fine: `lm app apply` warns, and the tab appears the
  day the hub has it. The same manifest works on an Open hub and on a Pro hub:

    ```yaml
    kind: App
    apiVersion: apps.lumnik.io/v1
    metadata:
      name: desk
    spec:
      title: Desk
      scope: desk
      icon: generic
      tabs: [fiches, inbox, search]
    ```

    ```
    $ lm app apply -f desk-app.yaml
    Applied app "desk" → scope desk
      tabs: fiches, inbox, search
    ! desk-app.yaml — 0 error(s), 1 warning(s)

      WARN     spec.tabs[2]
              tab 'search' is not offered by this hub; the declaration is kept and takes effect when a module offering it is active
              → offered here: [fiches, inbox]
    ```

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
