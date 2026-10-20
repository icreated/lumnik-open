# Querying Search

> Ask the index you declared: one query, optional filters, ranked hits. `lm search query` is
> the terminal door; `GET /api/search` is the one an application calls.

| | |
|---|---|
| **Command** | `lm search query "TEXT" [--entity E] [--facet field=value]… [--limit N] [--cursor C]` |
| **Door** | `GET /api/search` |
| **Who** | `lm_user`, bounded by the entity's scopes |
| **Needs** | a [Search provider built into the hub](search-indexes.md#choosing-a-provider), and a declared **and built** [`kind: SearchIndex`](search-indexes.md) |
| **Not to be confused with** | `lm ask`: that one takes a *question* and counts or aggregates (and shows the SQL it ran). This one takes *keywords* and finds records, the same answer every time, with no model |

## Quickstart — declare, build, ask

Every screen on this page reads the customers of the [demo desk](../apps/demo-desk.md): fourteen
fictional customers fused from an ERP export and a CRM export. Land the sources first
([Landing the sources](../apps/demo-desk.md#landing-the-sources)), then apply the entity, then
declare the index. Search needs a provider in the hub — the published image has none, see
[Choosing a provider](search-indexes.md#choosing-a-provider).

The index is the one from [the declaration page](search-indexes.md#quickstart): the customer's
name matched with tolerance for typos, the code matched exactly, the city as words and as a filter,
the segment as a filter.

```yaml
--8<-- "docs/search/search-indexes/10-customers.yaml"
```

From the repository root:

```
$ lm entity apply -f docs/apps/demo/entities/10-customer.yaml
Applied entity "AcmeCustomer" → connector."v_acmecustomer"
```

```
$ lm search apply -f docs/search/search-indexes/10-customers.yaml
Applied search index "customers" → entity AcmeCustomer (valid)
```

```
$ lm search rebuild customers --wait
Name              customers
Status            ready
Live generation   19
Documents         14
Lag               0s
Last rebuild      aa7b5014-3dbe-4f0d-8960-133950183b7c (ready)
```

`rebuild --wait` makes the index answer; declaring it only stores the declaration. Without `--wait`
the command returns once the run is claimed, and a query right away gets the
[answer for an index that is not built yet](#when-the-answer-is-not-no-results).

`lm search status customers` prints the same card at any time: status, documents, `Lag` behind the
data, and the last error if the build failed. When the database refused a step, the last error
gives only the database's error code (its SQLSTATE), because the database's own message can quote
stored values. The full message is in the hub's log, on the warning that names the index.

Now ask. `--entity` narrows the search to one entity; without it, every entity you may read is
searched and the hits of several entities come back mixed (the first column says which is which).

```
$ lm search query "hydraulique lyon" --entity AcmeCustomer
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1000  0.77   city=*Lyon* | code=P-1000 | name=Hydraulique Lyon 1000 | segment=GOLD
AcmeCustomer  code=P-1010  0.41   city=Milano | code=P-1010 | name=Hydraulique Milano 1010 | segment=SILVER

facets:
city      Lyon (1)  Milano (1)
segment   GOLD (1)  SILVER (1)

2 hits shown · as of 2026-10-06 08:48
```

## Reading a hit

| Column | Meaning |
|---|---|
| `ENTITY`, `KEY` | what the hit is. The key is the entity's own — read the full record with it (`lm entity get AcmeCustomer --filter code=P-1000`) |
| `SCORE` | how close the hit is. **Compare scores inside one answer only**: they rank this answer, they do not measure anything across queries or indexes |
| `MATCH` | the fields the index declares for this hit — the fields that matched first, then the others alphabetically; an empty field is left out. Words found in a `text` field are between `*stars*` |
| `facets:` | for each filterable field, the values among **all** the matches and how many — not only the ones on this page |
| `as of` | how fresh the index is: it shows the data as of that moment. Trust it, not your clock |

## What a query is

It is **one argument**, 1 to 200 characters, put in quotes when it contains spaces. The quotes are for your
shell; they do not make a phrase search (there is none). Without them the shell hands over several
arguments, and the command refuses rather than drop words:

```
$ lm search query hydraulique lyon --entity AcmeCustomer
Error: search query takes ONE argument — quote it when it has spaces, e.g. lm search query "hydraulique lyon" (got 2 arguments)
```

What a word does depends on the **match mode** the index gives the field it lands in (see
[Fields](search-indexes.md#fields-searchable-returned-faceted)):

| Mode | What you can type | Seen above |
|---|---|---|
| `fuzzy` | a name with a typo, a missing accent, a spelling variant | `Hydrolique` finds *Hydraulique*, `etancheite` finds *Étanchéité* |
| `exact` | the whole code | `P-1008` — and the exact match scores highest |
| `text` | words, understood in the index's `language` (plurals, accents) | `Lyon` on the city, shown between stars |
| `prefix` | the beginning of something typed | not used by this index |

A typo is absorbed by a `fuzzy` field:

```
$ lm search query Hydrolique --entity AcmeCustomer
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1000  0.32   city=Lyon | code=P-1000 | name=Hydraulique Lyon 1000 | segment=GOLD

facets:
city      Lyon (1)
segment   GOLD (1)

1 hit shown · as of 2026-10-06 08:48
```

and so is a missing accent:

```
$ lm search query etancheite --entity AcmeCustomer
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1004  0.48   city=Kraków | code=P-1004 | name=Étanchéité Kraków 1004 | segment=SILVER

facets:
city      Kraków (1)
segment   SILVER (1)

1 hit shown · as of 2026-10-06 08:48
```

A code that matches exactly comes first:

```
$ lm search query P-1008 --entity AcmeCustomer
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1008  1.23   city=Lyon | code=P-1008 | name=Filtration Lyon 1008 | segment=GOLD

facets:
city      Lyon (1)
segment   GOLD (1)

1 hit shown · as of 2026-10-06 08:48
```

**A `fuzzy` field ranks by resemblance; it does not demand every word.** The first screen of this page asked for
`"hydraulique lyon"` and got two customers: the one that matches the name *and* the city first, and the
other *Hydraulique* second. Read the score, not only the presence.

How several words combine **depends on the provider**:

| Provider | Words of a `text` field | Words of a `fuzzy` field | Words of a `prefix` field |
|---|---|---|---|
| PostgreSQL (these screens) | all must be present — an added word **narrows** | ranked by resemblance | all must be present |
| OpenSearch | any may match — an added word can **widen** | any may match | all must be present |

Check a query on the provider you run before relying on it. On PostgreSQL, two cities cannot both be
a customer's city:

```
$ lm search query "lyon porto" --entity AcmeCustomer
0 hits
```

```
$ lm search query xyzzy --entity AcmeCustomer
0 hits
```

**What a query does not have**: no `AND`, `OR` or `NOT`, no phrase search, no `field:value`. A `-`
or a quote is only punctuation. Here `-hydraulique` excludes nothing — the answer is the same two
customers as above:

```
$ lm search query "lyon -hydraulique" --entity AcmeCustomer
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1000  0.77   city=*Lyon* | code=P-1000 | name=Hydraulique Lyon 1000 | segment=GOLD
AcmeCustomer  code=P-1010  0.41   city=Milano | code=P-1010 | name=Hydraulique Milano 1010 | segment=SILVER

facets:
city      Lyon (1)  Milano (1)
segment   GOLD (1)  SILVER (1)

2 hits shown · as of 2026-10-06 08:48
```

## Narrow with filters

`--facet field=value` keeps only the hits whose field has that value. It works on the fields the index
declares `facet: true` (here `city` and `segment`) and can be repeated, one value per field.

```
$ lm search query hydraulique --entity AcmeCustomer --facet segment=GOLD
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1000  0.55   city=Lyon | code=P-1000 | name=Hydraulique Lyon 1000 | segment=GOLD

facets:
city      Lyon (1)
segment   GOLD (1)

1 hit shown · as of 2026-10-06 08:48
```

```
$ lm search query hydraulique --entity AcmeCustomer --facet segment=BRONZE
0 hits
```

A filter on a field the index does not declare as a facet is refused, naming the field:

```
$ lm search query hydraulique --entity AcmeCustomer --facet code=P-1000
Error: API error 400: filters key 'code' is not a facet field on any eligible SearchIndex
```

## Pages and the cursor

`--limit` is 1 to 100 (the hub's default is 20). Outside that range the hub refuses and says so —
the command never quietly changes the number you typed:

```
$ lm search query hydraulique --entity AcmeCustomer --limit 0
Error: API error 400: limit must be between 1 and 100
```

When there is more, the answer ends with the exact command for the next page. Here with `--limit 1`
(note that the facets still count the whole match, not the page):

```
$ lm search query hydraulique --entity AcmeCustomer --limit 1
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1000  0.55   city=Lyon | code=P-1000 | name=Hydraulique Lyon 1000 | segment=GOLD

facets:
city      Lyon (1)  Milano (1)
segment   GOLD (1)  SILVER (1)

1 hit shown · as of 2026-10-06 08:48
more: lm search query hydraulique --entity AcmeCustomer --limit 1 --cursor eyJzY29yZSI6MC41NDU0NTQ1NjE3MTAzNTc3LCJzZWFyY2hJbmRleCI6ImN1c3RvbWVycyIsImRvY3VtZW50SWQiOiIxfDY6UC0xMDAwIiwiZmluZ2VycHJpbnQiOiJzaGEyNTY6MzllODY0N2M5N2NjMzFkMDQyMGJmZjNmM2U1MDcwN2YxMTU4ODYyMDJjODY5MWJlMTBkOTk4ZTc1MmZkZTBiMSIsImVsaWdpYmxlIjp7ImN1c3RvbWVycyI6MTZ9fQ
```

Paste that line — it carries the cursor — to get the next page:

```
$ lm search query hydraulique --entity AcmeCustomer --limit 1 --cursor <cursor>
ENTITY        KEY          SCORE  MATCH
AcmeCustomer  code=P-1010  0.50   city=Milano | code=P-1010 | name=Hydraulique Milano 1010 | segment=SILVER

facets:
city      Lyon (1)  Milano (1)
segment   GOLD (1)  SILVER (1)

1 hit shown · as of 2026-10-06 08:48
```

(The cursor is long; it is shortened to `<cursor>` here, nothing else is changed.)

**A cursor is not a snapshot.** If the indexes being searched change between two pages — one is rebuilt,
one finishes building, one is removed — the hub refuses the old cursor rather than mix two states in one
list. The message talks about the *set of indexes*; what it means is "start this search over":

```
$ lm search query hydraulique --entity AcmeCustomer --limit 1 --cursor <cursor>
Error: API error 400: eligible SearchIndex set changed since this cursor was issued
```

## In a script

`-o json` (or `-o yaml`) prints the hub's answer. Standard output stays clean for the next tool: when
the answer is empty, the explanation of why goes to standard error.

```
$ lm search query hydraulique --entity AcmeCustomer -o json | jq '{hits: [.hits[] | {code: .key.code, score}], facets, asOf}'
{
  "hits": [
    {
      "code": "P-1000",
      "score": 0.5454545617103577
    },
    {
      "code": "P-1010",
      "score": 0.5
    }
  ],
  "facets": {
    "city": [
      {
        "value": "Lyon",
        "count": 1
      },
      {
        "value": "Milano",
        "count": 1
      }
    ],
    "segment": [
      {
        "value": "GOLD",
        "count": 1
      },
      {
        "value": "SILVER",
        "count": 1
      }
    ]
  },
  "asOf": "2026-10-06T06:49:07.399715Z"
}
```

## When the answer is not "no results"

Several situations look like an empty answer and are not. `lm search query` tells them apart from a
real "nothing matches" (which is a plain `0 hits`, as for `xyzzy` above) and says what to do.

**The index was deleted a moment ago.** The old documents may still answer until the
[purge](search-indexes.md#deleting-and-the-purge-it-leaves-behind) completes. Once it has:

```
$ lm search rm customers
Type 'delete' to delete search index customers over entity AcmeCustomer — its projected documents are purged: delete
search index customers deleted
```

```
$ lm search query hydraulique --entity AcmeCustomer
0 hits
no SearchIndex is declared over AcmeCustomer (that your scopes can read) — there is nothing to search. Declare one: lm search apply -f <manifest>
```

**Nothing is declared over that entity** — what the screen above says: declare an index
(`lm search apply -f`).

**The index is declared but not built.** Declaring stores the declaration; building makes it answer:

```
$ lm search apply -f docs/search/search-indexes/10-customers.yaml
Applied search index "customers" → entity AcmeCustomer (valid)
```

```
$ lm search query hydraulique --entity AcmeCustomer
0 hits
SearchIndex "customers" over AcmeCustomer is declared but not built yet: lm search rebuild customers --wait
```

**The build failed.** `lm search status NAME` names the error, and the query says the last build
failed rather than "not built yet". Quoted (`…` stands for the cause):

```
SearchIndex "customers" over AcmeCustomer has nothing built: its last build failed: IllegalStateException: entity 'AcmeCustomer' cannot be indexed: … — read it with `lm search status customers`, fix the cause, then lm search rebuild customers --wait
```

The error above (no `_canonical_key` column) is fixed as in
[An entity applied before Search](search-indexes.md#an-entity-applied-before-search).

Three more answers:

| You see | It means | Do |
|---|---|---|
| `search is not installed on this hub — no Search provider is built into it …` | the hub has no Search provider; this is **not** an empty result | build the hub with one: `deploy/selfhost/up.sh --search` (PostgreSQL) or `--search-cluster` (OpenSearch), closed edition |
| `search is degraded: <reason> — retry in 60s` | the provider is installed but failing. Reasons: `backend_unreachable` (cannot be reached), `backend_auth_failed` (refused the hub's credentials), `backend_error` (answered with an error), `unsupported_distribution` (not a supported distribution), `cluster_red` (red cluster health) | fix the backend named by the reason; ingestion goes on meanwhile |
| `SearchIndex "NAME" over ENTITY is invalid: <reason>` | an entity was re-applied without a column the index declares | fix the manifest and apply it again |

Only an integrator is told *not built* or *being built*; any user is told the other cases.

## Over HTTP

For an application. The CLI sends one request:

| Parameter | Meaning |
|---|---|
| `q` | the query, 1 to 200 characters (required) |
| `entity` | one entity name; absent means every entity you may read |
| `limit` | 1 to 100, default 20; outside the range is refused with `400` |
| `cursor` | the `nextCursor` of the previous page |
| `facet.<field>` | a filter on a declared facet field; at most 10 fields, each once |
| `scope` | a métier scope: keep only the entities that carry it, among those you may read (what the PWA sends). See below |

`scope=` in detail:

- absent: no narrowing; hits and facets are narrowed together when present;
- empty, padded, repeated or not a lowercase identifier: `400`;
- a scope-bound user who does not hold it: `403 {"error":"scope not granted"}`; an unbound user may
  name any scope and is narrowed to it;
- with `entity`, the entity must carry the scope, else the same `403`.

```
$ curl -s -H "Authorization: Bearer $TOKEN" \
    "$LUMNIK_URL/api/search?q=hydraulique&entity=AcmeCustomer&limit=1" | jq
{
  "hits": [
    {
      "entity": "AcmeCustomer",
      "key": {
        "code": "P-1000"
      },
      "score": 0.5454545617103577,
      "fields": {
        "city": "Lyon",
        "name": "Hydraulique Lyon 1000",
        "code": "P-1000",
        "segment": "GOLD"
      },
      "highlights": {
        "city": [
          "Lyon"
        ]
      },
      "canonicalLink": "/api/entities/AcmeCustomer?code=P-1000"
    }
  ],
  "facets": {
    "segment": [
      {
        "value": "GOLD",
        "count": 1
      },
      {
        "value": "SILVER",
        "count": 1
      }
    ],
    "city": [
      {
        "value": "Lyon",
        "count": 1
      },
      {
        "value": "Milano",
        "count": 1
      }
    ]
  },
  "nextCursor": "eyJzY29yZSI6MC41NDU0NTQ1NjE3MTAzNTc3LCJzZWFyY2hJbmRleCI6ImN1c3RvbWVycyIsImRvY3VtZW50SWQiOiIxfDY6UC0xMDAwIiwiZmluZ2VycHJpbnQiOiJzaGEyNTY6MzllODY0N2M5N2NjMzFkMDQyMGJmZjNmM2U1MDcwN2YxMTU4ODYyMDJjODY5MWJlMTBkOTk4ZTc1MmZkZTBiMSIsImVsaWdpYmxlIjp7ImN1c3RvbWVycyI6MTh9fQ",
  "asOf": "2026-10-06T06:49:57.211808Z"
}
```

| Field | Meaning | When absent or `null` |
|---|---|---|
| `hits[].key`, `score`, `fields`, `highlights` | the hit, as the CLI prints it | — |
| `hits[].canonicalLink` | the record's address | — |
| `hits[].title`, `titleField` | the business title and its field | `null` unless the index [declares one](search-indexes.md#choosing-a-title) |
| `hits[].links` | `field` and `url` templates | absent unless the index [links to your ERP](search-indexes.md#linking-a-result-to-your-erp) |
| `fieldLabels` | `{entity: {column: label}}`, the [human names](search-indexes.md#human-names-for-fields) | absent when none is labelled |
| `facets` | per facet field, values and counts over the whole match | — |
| `nextCursor` | the next page's cursor | `null` on the last page |
| `asOf` | the index's freshness | — |

| Status | Meaning |
|---|---|
| `400` | what you sent is wrong; the message says what |
| `403 {"error":"scope not granted"}` | an entity or scope you may not read |
| `501` | no provider installed |
| `503` with a `reason` | the provider is degraded |

To check the engine without searching, ask for its capability (no `lm` command prints it):

```
$ curl -s -H "Authorization: Bearer $TOKEN" "$LUMNIK_URL/api/search/capability"
{"state":"available","provider":"postgres","reason":null,"version":null}
```

## Who sees which hits

A hit is shown to whoever may read its entity: the index inherits the entity's scopes and they are
checked when you ask, not when the index was built. A user bound to some scopes sees the entities where
they hold at least one of them. An entity that does not exist and one you may not read answer the same
`403` on purpose:

```
$ lm search query hydraulique --entity Unknown
Error: access refused (403): scope not granted
```

An app narrows this further: the PWA sends the app's own scope (`scope=` below), so a search opened from
the `stock` app only ever shows the entities that carry `stock`, even to a user who holds other scopes too.

## In the PWA

The [métier PWA](../apps/pwa.md#recherche-search-the-business-data) has a Recherche tab that asks this same door, for its own métier, and shows the results to people who never open a terminal.

## See also

- [Search indexes — declaring them](search-indexes.md): the manifest, the match modes, the purge, choosing a provider.
- [CLI reference](../cli/lm-reference.md): every `lm search` command.
- [Ask your data](../rag/http-api.md): the other way in, for questions rather than keywords.
