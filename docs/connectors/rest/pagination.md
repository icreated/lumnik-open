# Pagination

> How an endpoint asks for the next page. Most APIs follow a convention that already has a
> name — declare the preset. When none fits, six strategies are written out by hand.

This page serves [REST](../rest.md) and [GraphQL](../graphql.md): both are the same engine, and
`relay` is what a GraphQL endpoint declares.

## Presets

Declare the preset; it is expanded at apply time into the real strategy:

| Preset | For | Expands to |
|---|---|---|
| `spring-page` | Spring Data `Page` | `page` (`$.content`, `$.totalPages`, 0-indexed) |
| `spring-slice` | Spring Data `Slice` (no total) | `page` + `last_path: $.last` |
| `spring-hateoas` | Spring Data REST | `body-link` (`$._links.next.href`, `$._embedded.{items}` — needs `items`) |
| `micronaut` | Micronaut Data `Page` | `page` (`$.content`, `$.totalPages`) |
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

When no preset fits, write the strategy out:

| Strategy | Use for | Required fields | Optional fields (default) |
|---|---|---|---|
| `cursor` | Stripe, OpenAI, most modern APIs | cursor_param, cursor_path, has_more_path, response_path | cursor_binding (query), total_path |
| `page` | Spring, Laravel, anything with page numbers | page_param, response_path | size_param, size (50), start_page (1), total_pages_path, total_path, has_more_path, last_path, empty_check (true) |
| `offset` | legacy REST | offset_param, limit_param, response_path | limit (100), total_path |
| `link-header` | GitHub | response_path | rel (next), total_path |
| `body-link` | Django REST, JSON:API, NestJS, HATEOAS | link_path, response_path | total_path |
| `relay` | GraphQL Connections | cursor_path, has_more_path, response_path; plus cursor_param **or** cursor_var | cursor_binding (query), total_path |
| `custom` | — | — | refused — see below |

`page` stops on whichever of `total_pages_path` / `has_more_path` / `last_path` is present —
and, with `empty_check: true` (the default), on the first empty page.

`total_path` is accepted by every strategy; it also checks the run's row count — see
[The declared total](../rest.md#the-declared-total-a-witness-on-the-count).

!!! warning "`kind: custom` is refused"
    `lm validate` and `lm apply` stop on it with `custom pagination is not supported; use page,
    offset, cursor, link-header or body-link`. A connector applied before that check still meets
    the same sentence in its run. Use one of the six strategies above.

!!! warning "Page size is a knob on `page` and `offset` only"
    `cursor`, `body-link` and `link-header` send **no size parameter**: the server's default
    page size governs the run.

    To ask for a larger page, put it in `path:`, as
    [`20-two-endpoints.yaml`](../rest.md#one-connector-one-api) does with
    `path: /drf/partners?page_size=500`. `relay` takes its size from `first:` in the query.

The `link-header` strategy written out, with an API key — the next page is in an RFC 5988
`Link` header:

```yaml
--8<-- "docs/connectors/rest/demo/30-link-header.yaml"
```

```
$ lm run list --connector acme-header
ID        CONNECTOR    ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
539d998f  acme-header  partners  Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
```

## Cursor binding

For `cursor` and `relay`, `cursor_binding` picks where the next-page cursor is injected:

| Binding | Cursor goes into | Needs |
|---|---|---|
| `query` (default) | a query parameter | cursor_param |
| `header` | an HTTP header | cursor_param (the header name) |
| `variable` | the POST body's `variables.<name>` | cursor_var — `relay` on a graphql endpoint only |

Both halves of that last rule are refused at apply:

```
$ lm apply -f bad-binding.yaml
✗ bad-binding.yaml — 2 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].pagination.cursor_binding
          cursor_binding=variable is only valid for graphql endpoints; non-graphql endpoints use cursor_binding=query|header

  ERROR    spec.endpoints[0].pagination.cursor_binding
          cursor_binding=variable is only valid for pagination.kind=relay; cursor pagination uses cursor_binding=query|header

Fix errors above before running 'lm apply -f bad-binding.yaml'.
Error: validation failed: 2 error(s)
```

Both errors must be fixed.

> **A pure GraphQL API** (POST a query, cursor in `variables`): see [GraphQL](../graphql.md).

## See also

- [REST](../rest.md) — the engine underneath
- [GraphQL](../graphql.md) — `relay` with a variable binding
- [The demo API](demo-api.md) — the dialects these examples read
