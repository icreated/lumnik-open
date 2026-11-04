# Pagination

> How an endpoint asks for the next page. Most APIs follow a convention that already has a
> name: declare its preset. When none fits, write one of seven strategies.

This page serves [REST](../rest.md) and [GraphQL](../graphql.md): both are the same engine, and
`relay` is what a GraphQL endpoint declares.

## Presets

Declare the preset that matches the API's framework:

| Preset | For | Same as |
|---|---|---|
| `spring-page` | Spring Data `Page`, serialized as is | `page` (`$.content`, `$.totalPages`, 0-indexed, total `$.totalElements`) |
| `spring-paged-model` | Spring Data `Page` under `VIA_DTO` (`PagedModel`) | `page` (`$.content`, `$.page.totalPages`, 0-indexed, total `$.page.totalElements`) |
| `spring-slice` | Spring Data `Slice` (no total) | `page` + `last_path: $.last` |
| `spring-hateoas` | Spring Data REST | `body-link` (`$._links.next.href`, `$._embedded.{items}`, total `$.page.totalElements` — needs `items`) |
| `micronaut` | Micronaut Data `Page` | `page` (`$.content`, 0-indexed, total `$.totalSize`; `$.totalPages` too, which Micronaut 3 sends and 4 does not) |
| `drf-page` | Django REST `PageNumberPagination` | `body-link` (`$.next`, `$.results`, total `$.count`) |
| `drf-limit-offset` | Django REST `LimitOffsetPagination` | `offset` (`$.count`, `$.results`) |
| `fastapi` | fastapi-pagination | `page` (`$.items`, `$.pages`, 1-indexed, total `$.total`) |
| `nestjs` | nestjs-paginate | `body-link` (`$.links.next`, `$.data`, total `$.meta.totalItems`) |
| `json-api` | JSON:API | `body-link` (`$.links.next`, `$.data`) |
| `sequelize` | Node/Sequelize | `offset` (`$.count`, `$.rows`) |
| `stripe` | Stripe-style cursor | `cursor` (`$.has_more`, `$.data`) |
| `prestashop` | PrestaShop's Webservice | `offset` in one parameter (`limit=0,50`, `$.{resource}` — needs `resource`) |

```yaml
pagination: { kind: spring-page }                       # 7 lines → 1
pagination: { kind: spring-page, size: 500 }            # override any expanded field
pagination: { kind: spring-hateoas, items: products }   # {items} → $._embedded.products
pagination: { kind: prestashop, resource: products, limit: 50 }   # ?limit=0,50 then ?limit=50,50… → $.products
```

A Spring `Page` comes in two shapes. Look at one response:

```text
{"content":[…],"totalPages":65,"totalElements":130,…}               → spring-page
{"content":[…],"page":{"size":50,"number":0,"totalPages":65,…}}     → spring-paged-model
```

The second is the shape Spring recommends since Spring Data 3.3
(`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`). Read with `spring-page`, it
still loads every row, but asks for one empty page after the last.

`nestjs` and `json-api` differ by one line: `nestjs` also reads the total its pages carry, `json-api` has none by the spec. Two presets require an override: `spring-hateoas` needs
`items` for its `$._embedded.{items}` placeholder, and `prestashop` needs `resource`, the list's name
(`products`, `categories`, `customers`…).

PrestaShop answers XML unless asked. Either read it as it answers, with `response_format: xml` and
`response_path: $.products.product` ([An answer in XML](../rest.md#an-answer-in-xml)), or put
`output_format=JSON` in the endpoint's `path:`. It gives no total, so the read stops on a short page.

Spring Data REST puts no id in its items: key them on their self link's last segment,
[a key read from a link](../rest.md#a-key-read-from-a-link).

## Strategies

When no preset fits, write the strategy out. Each one below shows what the API answers, then
the block that reads it.

**`cursor`** — the next page starts after the last item:

```json
{ "data": [ {"id": "cus_41"}, {"id": "cus_42"} ], "has_more": true }
```

```yaml
pagination:
  kind: cursor
  cursor_param: starting_after      # sent as ?starting_after=cus_42
  cursor_path: $.data[-1:].id       # the id of the last item
  has_more_path: $.has_more
  response_path: $.data
```

An API whose cursor is the "more" signal — a token that is empty or absent on the last page, like
the Kubernetes API's `metadata.continue` — needs no `has_more_path`:

```json
{ "metadata": { "continue": "eyJ2IjoibWV0YS5rOHMuaW8vdjEi" }, "items": [ … ] }
```

```yaml
path: /api/v1/pods?limit=500
pagination:
  kind: cursor
  cursor_param: continue            # sent as ?continue=eyJ2…
  cursor_path: $.metadata.continue  # empty on the last page: the read stops
  response_path: $.items
```

- With `has_more_path`, the flag decides, and it must read a boolean: a string there is refused
  as `pagination.has_more_path must read a boolean, not a string`. A flag saying "more" on a page
  whose cursor is empty is refused too (`… says more pages, but cursor_path reads no cursor`):
  the rest of the source would otherwise go unread with the run reported complete.
- Without it, the cursor decides: an empty or absent cursor ends the read, and so does an empty
  page, whatever cursor it carries. `lm validate` says which mode the endpoint is in.
- A source that sends back the cursor it was just given, while saying there is more, fails the
  run: `Endpoint 'events': the source sent back the cursor it was given while saying more pages —
  the cursor is not reaching it (check cursor_binding, and cursor_param or cursor_var)`. The usual
  cause is a cursor sent where the API does not read it: in the query when it expects a header, or
  under another variable name for a GraphQL `relay` ([Cursor binding](#cursor-binding)).
- A path reads one value. A slice such as `$.data[-1:].id` is fine — empty on an empty page; a
  path that reads several values (`$..endCursor` across nested connections) is refused rather
  than one of them picked.
- `total_path` and `total_pages_path` read a number, or its digits sent as text (`"1234"`).

**`page`** — page numbers:

```json
{ "content": [ … ], "number": 0, "totalPages": 5 }
```

```yaml
pagination:
  kind: page
  page_param: page                  # ?page=0, ?page=1, …
  size_param: size
  size: 500
  start_page: 0                     # the default is 1
  total_pages_path: $.totalPages
  response_path: $.content
```

`page` stops at `total_pages_path`, `has_more_path` or `last_path` (whichever you declare), and
on the first empty page.

**`offset`** — a row offset and a limit:

```json
{ "data": [ … ], "total": 2400 }
```

```yaml
pagination:
  kind: offset
  offset_param: offset              # ?offset=0&limit=500, then ?offset=500…
  limit_param: limit
  limit: 500
  response_path: $.data
```

Some APIs carry both in one parameter, `offset,count` — PrestaShop's `?limit=0,50`, MySQL's
`LIMIT 0,50`. Name the same parameter twice: `offset_param: limit` and `limit_param: limit` send
`?limit=0,500` first, then `?limit=500,500`…

**`body-link`** — the answer carries the next page's URL:

```json
{ "results": [ … ], "next": "https://api.example.com/partners?page=2" }
```

```yaml
pagination:
  kind: body-link
  link_path: $.next
  response_path: $.results
```

The link is a URL, or a link object carrying it as `href` — JSON:API allows both, and Drupal writes
the object (`"next": { "href": "…" }`): `link_path: $.links.next` reads either. Anything else is
refused, naming the JSON type it read: `pagination.link_path must read a URL or a link object with
href, not an object` for an object without `href`, `… not a number` for a number.

**`none`** — the answer holds the whole list, and there is no next page. Older applications
often answer this way, such as Spring PetClinic before Spring Boot:

```json
{ "vetList": [ {"id": 1, "lastName": "Carter"}, {"id": 3, "lastName": "Douglas"} ] }
```

```yaml
pagination:
  kind: none
  response_path: $.vetList
```

One request, every row it holds. If the source does page, `none` reads the first page only:
check the count against the application, or declare `total_path`.

**`link-header`** — the next URL is in the `Link` header (GitHub style). Only `response_path` is
needed: [the example below](#a-full-example-link-header).

**`relay`** — GraphQL Connections: [GraphQL](../graphql.md).

`total_path` is accepted by every strategy. It checks the run's row count against what the API
declares: [The declared total](../rest.md#the-declared-total-a-witness-on-the-count). When the API
sends that count in a response header (`X-Total-Count`), name it with `total_header` instead.

!!! warning "Page size is a knob on `page` and `offset` only"
    `cursor`, `body-link` and `link-header` send **no size parameter**: the server's default
    page size governs the run.

    To ask for a larger page, put it in `path:`, as
    [`20-two-endpoints.yaml`](../rest.md#one-connector-one-api) does with
    `path: /drf/partners?page_size=500`. `relay` takes its size from `first:` in the query.

!!! warning "Not in `path:`: a parameter the strategy sends itself"
    `page_param`, `size_param`, `offset_param`, `limit_param` and a query `cursor_param` go out on every
    request. Written in `path:` too, one would reach the API twice, and the API would keep one of the
    two. `lm validate` refuses it. Set the page size with `size:` (or `limit:`) instead.

### A full example: `link-header`

The next page is in the response's `Link` header, and the API wants a key:

```yaml
--8<-- "docs/connectors/rest/demo/30-link-header.yaml"
```

```
$ lm run list --connector acme-header
ID        CONNECTOR    ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
539d998f  acme-header  partners  Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
```

## Cursor binding

For `cursor` and `relay`, `cursor_binding` says where the cursor goes:

| Binding | Cursor goes into | Needs |
|---|---|---|
| `query` (default) | a query parameter | cursor_param |
| `header` | an HTTP header | cursor_param (the header name) |
| `variable` | the POST body's `variables.<name>` | cursor_var — `relay` on a graphql endpoint only |

An API that wants the cursor in a header:

```yaml
pagination:
  kind: cursor
  cursor_binding: header
  cursor_param: X-Next-Cursor       # the header's name
  cursor_path: $.next_cursor
  has_more_path: $.has_more
  response_path: $.items
```

`variable` anywhere else than a GraphQL `relay` is refused at `lm apply`.

> **A pure GraphQL API** (POST a query, cursor in `variables`): see [GraphQL](../graphql.md).

## Every field

| Strategy | Required fields | Optional fields (default) |
|---|---|---|
| `cursor` | cursor_param, cursor_path, response_path | has_more_path (none: a non-empty cursor means more), cursor_binding (query), total_path or total_header |
| `page` | page_param, response_path | size_param, size (50), start_page (1), total_pages_path, total_path (with size_param, the read ends once the rows read reach it) or total_header, has_more_path, last_path, empty_check (true) |
| `offset` | offset_param, limit_param, response_path | limit (100), total_path or total_header |
| `link-header` | response_path | rel (next), total_header or total_path |
| `body-link` | link_path, response_path | total_path or total_header |
| `none` | response_path | total_path or total_header |
| `relay` | cursor_path, has_more_path, response_path; plus cursor_param **or** cursor_var | cursor_binding (query), total_path or total_header |

## See also

- [REST](../rest.md) — the engine underneath
- [GraphQL](../graphql.md) — `relay` with a variable binding
- [The demo API](demo-api.md) — the dialects these examples read
