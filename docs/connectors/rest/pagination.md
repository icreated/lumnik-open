# Pagination

> How an endpoint asks for the next page. Most APIs follow a convention that already has a
> name: declare its preset. When none fits, write one of six strategies.

This page serves [REST](../rest.md) and [GraphQL](../graphql.md): both are the same engine, and
`relay` is what a GraphQL endpoint declares.

## Presets

Declare the preset that matches the API's framework:

| Preset | For | Same as |
|---|---|---|
| `spring-page` | Spring Data `Page` | `page` (`$.content`, `$.totalPages`, 0-indexed) |
| `spring-slice` | Spring Data `Slice` (no total) | `page` + `last_path: $.last` |
| `spring-hateoas` | Spring Data REST | `body-link` (`$._links.next.href`, `$._embedded.{items}` — needs `items`) |
| `micronaut` | Micronaut Data `Page` | `page` (`$.content`, `$.totalPages`, 0-indexed) |
| `drf-page` | Django REST `PageNumberPagination` | `body-link` (`$.next`, `$.results`) |
| `drf-limit-offset` | Django REST `LimitOffsetPagination` | `offset` (`$.count`, `$.results`) |
| `fastapi` | fastapi-pagination | `page` (`$.items`, `$.pages`, 1-indexed) |
| `nestjs` | nestjs-paginate | `body-link` (`$.links.next`, `$.data`) |
| `json-api` | JSON:API | `body-link` (`$.links.next`, `$.data`) |
| `sequelize` | Node/Sequelize | `offset` (`$.count`, `$.rows`) |
| `stripe` | Stripe-style cursor | `cursor` (`$.has_more`, `$.data`) |

```yaml
pagination: { kind: spring-page }                       # 7 lines → 1
pagination: { kind: spring-page, size: 500 }            # override any expanded field
pagination: { kind: spring-hateoas, items: products }   # {items} → $._embedded.products
```

`nestjs` and `json-api` expand identically. Only `spring-hateoas` requires an override:
`items` fills its `$._embedded.{items}` placeholder.

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

**`link-header`** — the next URL is in the `Link` header (GitHub style). Only `response_path` is
needed: [the example below](#a-full-example-link-header).

**`relay`** — GraphQL Connections: [GraphQL](../graphql.md).

`total_path` is accepted by every strategy. It checks the run's row count against what the API
declares: [The declared total](../rest.md#the-declared-total-a-witness-on-the-count).

!!! warning "Page size is a knob on `page` and `offset` only"
    `cursor`, `body-link` and `link-header` send **no size parameter**: the server's default
    page size governs the run.

    To ask for a larger page, put it in `path:`, as
    [`20-two-endpoints.yaml`](../rest.md#one-connector-one-api) does with
    `path: /drf/partners?page_size=500`. `relay` takes its size from `first:` in the query.

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
| `cursor` | cursor_param, cursor_path, has_more_path, response_path | cursor_binding (query), total_path |
| `page` | page_param, response_path | size_param, size (50), start_page (1), total_pages_path, total_path, has_more_path, last_path, empty_check (true) |
| `offset` | offset_param, limit_param, response_path | limit (100), total_path |
| `link-header` | response_path | rel (next), total_path |
| `body-link` | link_path, response_path | total_path |
| `relay` | cursor_path, has_more_path, response_path; plus cursor_param **or** cursor_var | cursor_binding (query), total_path |

## See also

- [REST](../rest.md) — the engine underneath
- [GraphQL](../graphql.md) — `relay` with a variable binding
- [The demo API](demo-api.md) — the dialects these examples read
