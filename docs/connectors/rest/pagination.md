# Pagination

> How an endpoint asks for the next page. Most APIs follow a convention that already has a
> name — declare the preset. When none fits, six strategies are written out by hand.

This page serves [REST](../rest.md) and [GraphQL](../graphql.md): both are the same engine, and
`relay` is what a GraphQL endpoint declares.

## Presets

Most APIs follow a convention that already has a name. Declare the preset and the engine
expands it at apply time into the real strategy:

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

`nestjs` and `json-api` expand identically — pick the one that names your source, the engine
sees the same thing. Only `spring-hateoas` requires an override: `items` is what its
`$._embedded.{items}` placeholder needs.

## Strategies

When no preset fits, write the strategy out — every field the preset would have
expanded to, in the manifest:

| Strategy | Use for | Required fields | Optional fields (default) |
|---|---|---|---|
| `cursor` | Stripe, OpenAI, most modern APIs | cursor_param, cursor_path, has_more_path, response_path | cursor_binding (query) |
| `page` | Spring, Laravel, anything with page numbers | page_param, response_path | size_param, size (50), start_page (1), total_pages_path, total_path, has_more_path, last_path, empty_check (true) |
| `offset` | legacy REST | offset_param, limit_param, response_path | limit (100), total_path |
| `link-header` | GitHub, JSON:API | response_path | rel (next) |
| `body-link` | Django REST, JSON:API, NestJS, HATEOAS | link_path, response_path | total_path |
| `relay` | GraphQL Connections | cursor_path, has_more_path, response_path; plus cursor_param **or** cursor_var | cursor_binding (query) |
| `custom` | — | response_path | a Kotlin script hook, **not wired yet** — see below |

`page` stops on whichever of `total_pages_path` / `has_more_path` / `last_path` is present —
and, with `empty_check: true` (the default), on the first empty page.

`total_path` does a second job beyond stopping the walk: it becomes a witness the run is
checked against — see [The declared
total](../rest.md#the-declared-total-a-witness-on-the-count).

!!! warning "`kind: custom` validates, then fails on the second page"
    The Kotlin-script strategy is a stub in this release. A manifest declaring it applies
    cleanly, `lm source rest test` reads its first page and answers `ok`, and the run dies as
    soon as a second page is needed:

    ```
    ✖ run failed: Custom Kotlin pagination scheduled for Task 9 — KotlinScriptHost.executeSource integration
    ```

    Use one of the six strategies above, or the `path:` query-string trick, until it lands.

!!! warning "Page size is a knob on `page` and `offset` only"
    `cursor`, `body-link` and `link-header` send **no size parameter at all** — their first
    request is a bare GET — so the *server's* default page size governs the whole run. A server
    defaulting to 50 rows turns 2 400 partners into 48 requests instead of 5. To ask for a
    larger page, put the query string in the endpoint's `path:`
    (`path: /drf/partners?page_size=500`, as in the manifest above): it survives, because the
    request builder appends its own parameters with `&`. `relay` is different again — its page
    size is the `first:` argument you write in the query yourself.

The `link-header` strategy written out, with an API key on the side — the next page lives in an
RFC 5988 header and the body says nothing about paging:

```yaml
--8<-- "docs/connectors/rest/demo/30-link-header.yaml"
```

```
$ lm run list --connector acme-header
ID        CONNECTOR    ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
88481a89  acme-header  partners  Completed  2400  2400  0        0             2026-09-10 09:54  2026-09-10 09:54
```

## Cursor binding

For `cursor` and `relay`, `cursor_binding` picks where the next-page cursor is injected:

| Binding | Cursor goes into | Needs |
|---|---|---|
| `query` (default) | a query parameter | cursor_param |
| `header` | an HTTP header | cursor_param (the header name) |
| `variable` | the POST body's `variables.<name>` | cursor_var — `relay` on a graphql endpoint only |

Both halves of that last rule are refused at apply, and the refusal names the way out:

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

Two errors on one field, both true: a manifest that fixes only the first is still refused.

> **A pure GraphQL API** (POST a query, cursor in `variables`): see [GraphQL](../graphql.md).

## See also

- [REST](../rest.md) — the engine underneath
- [GraphQL](../graphql.md) — `relay`, and why its binding is a variable
- [The demo API](demo-api.md) — the dialects these examples read
