# REST

> Ingest any HTTP/JSON API: point at the base URL, declare the endpoints and how they page,
> apply. The rows land as typed columns plus the untouched JSON payload.

| | |
|---|---|
| **Type** | `rest-generic` |
| **Protocol** | HTTP/JSON (+ the GraphQL and SOAP request shapes) |
| **Target** | the table each endpoint declares in `target.table` (`ext.*`) |
| **Schedulable** | cron |
| **Edition** | Open |

Every endpoint declares its own `method`; a source usually needs `GET`. The connector sends
only the requests the manifest declares.

A response must be JSON, except on a [SOAP](soap.md) endpoint. Any other body, such as XML
or an HTML page, fails the run with its Content-Type:
`the response is not JSON (Content-Type application/xml)`. The run is never read as an empty page.

## Quickstart

!!! note "Every screen on this page reads a demo API that ships with the repo"
    One Python file, no dependencies, 2 400 fictional ACME partners under ten path dialects.
    Start it in a terminal of its own, and put its three credentials in the hub's registry:

    ```bash
    python3 docs/connectors/rest/demo/acme-api.py
    lm secret set ACME_BASIC_PW        # demo-pass
    lm secret set ACME_BEARER_TOKEN    # demo-token
    lm secret set ACME_API_KEY         # demo-key
    ```

    The manifests point at `http://host.docker.internal:8099`, because the hub runs in a
    container and `localhost` inside it is that container. What each dialect imitates, and
    which ones misbehave on purpose: [The demo API](rest/demo-api.md).

```yaml
--8<-- "docs/connectors/rest/demo/10-quickstart.yaml"
```

```
$ lm validate -f docs/connectors/rest/demo/10-quickstart.yaml
✓ 10-quickstart.yaml — valid
$ lm apply -f docs/connectors/rest/demo/10-quickstart.yaml
Applied connector "acme-partners" — 1 endpoint
```

Before running anything, send the first request a run would send:

```
$ lm source rest test acme-partners
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  GET     200     500    ok
```

`ITEMS` is the number of records read from that one page (the manifest asks for 500). Nothing
is written. Now run it:

```
$ lm connector run acme-partners
run scheduled: 9deb9cb6-d9bc-427f-ba27-5af060647f19
$ lm run list --connector acme-partners
ID        CONNECTOR      ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
9deb9cb6  acme-partners  partners  Completed  2400  2400  0        0             2026-10-19 01:39  2026-10-19 01:39
```

`STARTED` / `ENDED` print in your terminal's time zone; `schedule` is evaluated in UTC.

What landed in `ext.acme_partners` — the promoted columns, beside the whole payload:

```
lumnik=> SELECT external_id, name, city, credit_limit, updated_at
lumnik->   FROM ext.acme_partners ORDER BY external_id LIMIT 4;
 external_id |           name            |   city    | credit_limit |       updated_at       
-------------+---------------------------+-----------+--------------+------------------------
 1000        | Hydraulique Lyon 1000     | Lyon      |          500 | 2026-01-01 00:00:00+00
 1001        | Roulements Bruxelles 1001 | Bruxelles |          637 | 2026-01-01 00:01:00+00
 1002        | Fixations Milano 1002     | Milano    |          774 | 2026-01-01 00:02:00+00
 1003        | Pneumatique Porto 1003    | Porto     |          911 | 2026-01-01 00:03:00+00
(4 rows)
```

- **`promote` is a projection, not a filter.** Every field the source returned is in `data`
  (JSONB); the promoted ones are also copied into typed columns.
- **A field is shaped by its `promote[]` entry and its `transform:`.** A rule over the promoted
  columns — a filter, a Kotlin script — goes in the endpoint's `mapping`:
  [Run hooks over the promoted columns](#run-hooks-over-the-promoted-columns).
- **`unique_key` makes a re-run safe.** It becomes `external_id`, unique per tenant, and every
  write is an upsert — see [Storage layout](#storage-layout).
- **The password is never in the manifest.** `password_env` names an `lm secret` entry (or, if
  none, an environment variable on the hub).

## The manifest

```yaml
spec:
  base_url: https://api.example.com
  auth: { kind: bearer, token_env: EXAMPLE_TOKEN }
  default_rate_limit: 100/s
  schedule: "0 6 * * *"          # optional cron, evaluated in UTC by the hub scheduler
  window: "22:00-06:00"          # optional fence, UTC, end exclusive, may cross midnight
  graphql_path: /graphql         # only for GraphQL endpoints; this is the default
  headers:
    - { name: X-Api-Version, value: "2026-01" }
  endpoints:
    - id: charges                # kebab-case, unique in the connector
      method: GET                # GET | POST | PUT | PATCH | DELETE
      path: /v1/charges
      pagination: { kind: stripe }
      incremental: { mode: full }          # optional, this is the default
      target:
        kind: hybrid
        table: ext.example_charges
        unique_key: $.id
        promote:
          - { name: amount, path: $.amount, type: bigint }
```

- The path-shaped fields (`cursor_path`, `response_path`, `unique_key`, `link_path`, …) are
  JSONPath, read against the response body. `$.data[-1:].id` is "the `id` of the last element
  of `data`".
- Without `schedule`, the connector runs only on `lm connector run`.
- `window` fences the scheduled runs: a slot due outside it is skipped, and the next slot inside
  it runs. A manual run ignores the window.

An unknown key at the connector level of `spec` comes back as a warning. The manifest still
applies:

```
$ lm validate -f my-api.yaml
! my-api.yaml — 0 error(s), 1 warning(s)

  WARN     spec.headerz
          unknown key — ignored by the runtime
          → Remove it, or check the spelling
```

**A `promote` entry** copies one value of each item into a typed column:

- `type` is one of `text`, `bigint`, `integer`, `decimal`, `boolean`, `date`, `timestamptz`,
  `jsonb`.
- `name` is a new column. It cannot be a column the table already has: `id`, `tenant_id`,
  `external_id`, `row_hash`, `data`, `indexed_at`, `connector_id`, `endpoint_id`. Two entries
  cannot share a name.
- `path` must be a JSONPath.
- `lm validate` refuses a reserved name, a name used twice and a path that is not a JSONPath. It
  names the entry.
- `transform:` is optional, e.g. `from_epoch` for a number of seconds:
  [the list](#transform-a-promoted-value).

A value sent as text is read as the `type` says: `"2026-10-01T08:01:00Z"` as a `timestamptz`,
`"12"` as a `bigint`, `"true"` as a `boolean`.

A value that cannot be read is a **cell error**. The column stays empty for that row. The row is
still written, with the value in its payload, and the run goes on. `lm run get` counts these cells:

```
⚠ 1 cell(s) not delivered as declared (a hook, or a promoted value of the wrong type) — the rows were still written
```

A path with a filter or a wildcard, such as `$.status.conditions[?(@.type=='Ready')].status`:

- One value matches: the column holds it, `False` and not `["False"]`.
- Nothing matches: the column is empty.
- Several values match: one cell error.
- A `jsonb` column keeps the whole list.

A path without a filter keeps what it points at, an array included. A path the JSONPath library
cannot evaluate is one cell error. `first()` after a filter is one: write the filter alone.

### Transform a promoted value

A `promote` entry takes one `transform:`. A rule over several columns goes in the endpoint's
`mapping`: [Run hooks over the promoted columns](#run-hooks-over-the-promoted-columns).

| Value | Effect |
|---|---|
| `from_epoch` | Unix epoch **seconds** → timestamp (UTC). Milliseconds are not supported. |
| `parse_iso8601` | ISO-8601 text → timestamp |
| `from_odata_date` | An OData v2 date, as SAP Gateway writes it → timestamp (UTC). [The shapes](#read-an-odata-v2-date) |
| `to_lower` / `to_upper` | case |
| `trim` | removes the spaces around the value |
| `to_json_string` | writes the value as JSON text |
| `parse_quantity` | a number with a unit suffix → the number in its base unit: `100m` → `0.1`, `64Mi` → `67108864`, `99827146n` → `0.099827146`. Use `type: decimal`. |
| `last_path_segment` | a link → its last path segment, percent-decoded: `http://erp/products/SKU-00001?projection=full` → `SKU-00001`. A link with no segment reads no value. |

`parse_iso8601` reads these shapes:

| Source text | Stored |
|---|---|
| `2008-04-18T00:00:00` | `2008-04-18T00:00:00Z`: no offset means UTC |
| `2008-04-18 00:00:00` | `2008-04-18T00:00:00Z`: a space works too |
| `2008-04-18` | `2008-04-18T00:00:00Z`: a date alone is midnight |
| `2026-08-01T14:30:00+02:00` | `2026-08-01T12:30:00Z`: the offset is applied |

Any other shape leaves the column empty for that row and counts one cell error; the row is
written and keeps the value in its payload.

`parse_quantity` reads the notation Kubernetes uses, which many APIs share:

| Suffix | Factor |
|---|---|
| `n` `u` `m` | 10⁻⁹, 10⁻⁶, 10⁻³ |
| `k` `M` `G` `T` `P` `E` | 10³ … 10¹⁸ |
| `Ki` `Mi` `Gi` `Ti` `Pi` `Ei` | 2¹⁰ … 2⁶⁰ |
| `e3`, `E-3` | 10 to that power, at most 18 |

Read from a cluster's `metrics.k8s.io` — each value promoted twice, as sent and with the transform:

```yaml
promote:
  - { name: cpu,          path: "$.containers[0].usage.cpu",    type: text }
  - { name: cpu_cores,    path: "$.containers[0].usage.cpu",    type: decimal, transform: parse_quantity }
  - { name: memory,       path: "$.containers[0].usage.memory", type: text }
  - { name: memory_bytes, path: "$.containers[0].usage.memory", type: decimal, transform: parse_quantity }
```

```
lumnik=> SELECT pod, cpu, cpu_cores, memory, memory_bytes FROM ext.k8s_usage ORDER BY pod LIMIT 2;
             pod              |    cpu    |  cpu_cores  | memory | memory_bytes
------------------------------+-----------+-------------+--------+--------------
 catalog-api-54b5c97cdf-q9v2g | 99756659n | 0.099756659 | 316Ki  |       323584
 checkout-worker-0            | 44515n    | 0.000044515 | 8544Ki |      8749056
```

The suffix is case-sensitive: `m` is milli, `M` mega. `12 MB` or `1mi` is not a quantity: the
column stays empty and the run counts one cell error, as for any transform.

#### Read an OData v2 date

SAP Gateway sends a date as `/Date(<milliseconds>)/`, and a date with a zone as
`/Date(<milliseconds>±<minutes>)/`. `from_odata_date` reads the two:

| Source text | Stored |
|---|---|
| `/Date(1793577600000)/` | `2026-11-02T00:00:00Z` |
| `/Date(1793577600000+0060)/` | `2026-11-02T00:00:00Z`: the milliseconds are already UTC; the offset only says the zone the source showed |
| `/Date(-86400000)/` | `1969-12-31T00:00:00Z`: a date before 1970 |

Any other shape leaves the column empty for that row and counts one cell error; the row is
written and keeps the value in its payload. So does a date outside the years 0001 to 9999, and an
offset above 1440 minutes.

```yaml
promote:
  - { name: requested_delivery, path: "$.RequestedDeliveryDate", type: timestamptz, transform: from_odata_date }
```

#### A key read from a link

`unique_key` takes the same object form when the key needs a transform. Spring Data REST, for
example, puts no id in the item: the id is the last segment of its self link.

```yaml
pagination: { kind: spring-hateoas, items: products }
target:
  kind: hybrid
  table: ext.products
  unique_key: { path: $._links.self.href, transform: last_path_segment }
  promote:
    - { name: sku, path: $._links.self.href, transform: last_path_segment, type: text }
```

- **Never key on the whole link.** It carries the host the hub reached: the day the API moves
  behind https or another name, every row is written again beside the old one.
- An item whose key reads no value fails the run, as an item without the key's field does.

### Run hooks over the promoted columns

`transform:` changes one value. A rule over the row — keep some items, compute a column from
others — goes in the endpoint's `mapping`. It takes the [transformers](transformers.md) and
[Kotlin scripts](kotlin-hooks.md) of a csv-file connector:

```yaml
--8<-- "docs/connectors/rest/demo/96-mapping-hooks.yaml"
```

The script, from the hub's hooks directory:

```kotlin
--8<-- "docs/connectors/rest/hooks/credit-band.kts"
```

The run reads 2 400 partners, keeps the French ones, and says which script it ran:

```
$ lm run get 0ff895b1-1ba1-4703-a4f4-737203d3325e
ID        CONNECTOR           ENDPOINT  STATUS     IN    OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
0ff895b1  acme-partner-bands  partners  Completed  2400  300  2100     0             2026-10-29 20:35  2026-10-29 20:35
• script hooks:credit-band.kts  sha256 eb508f320131cff3ad56cc814743d4af4e7f805ab239f85c0248ab7791baf63d
```

```
lumnik=> SELECT external_id, name, credit_limit, credit_band FROM ext.acme_partner_bands
lumnik->   ORDER BY external_id::int LIMIT 2;
 external_id |         name          | credit_limit | credit_band
-------------+-----------------------+--------------+-------------
 1000        | Hydraulique Lyon 1000 |          500 | C
 1008        | Filtration Lyon 1008  |         1596 | C

lumnik=> SELECT data FROM ext.acme_partner_bands WHERE external_id = '1000';
 {"id": 1000, "city": "Lyon", "name": "Hydraulique Lyon 1000", "country": "FR", "updated_at": "2026-01-01T00:00:00Z", "credit_limit": 500}
```

- **A hook sees the promoted columns**, after their `transform:`, not the JSON. To read a
  field, promote it. A field the item lacks is in the row, empty.
- **A value is as the API sent it.** `"12"` stays text and `12` a number: the column's `type`
  applies when the row is written. A script reads `(cells["credit_limit"] as Number)` only when
  the API sends a number.
- **A column the mapping fills** is a `promote` entry without `path`. The entry gives its type.
- **`data` stays what the API returned.** The mapping changes the columns, never the payload.
- **An item a hook drops is counted** in `SKIPPED`: `IN = OUT + SKIPPED`. Under
  `on-error: dlq`, the dead letter is the item as the API sent it.
- **An item holding a NUL byte (`0x00`) goes to the dead letters,** mapping or not. The database
  cannot store it. The other items land.
- **One item is one row, mapped on its own.** `split-row` and `aggregator` are refused: a row
  lands beside the item it was read from. `sort-by` and `fill-down` are refused too: an item
  mapped alone has nothing around it to order or to carry from.
- **The mapping belongs to its endpoint.** `spec.mapping` and `spec.defaults.mapping` are
  refused: each endpoint promotes its own columns.

`lm validate` knows the columns. In the demo, replace the script with `- lower: { columns: [city] }`:
`city` is not promoted, and the hook is named by its path:

```
$ lm validate -f bad-column.yaml
✗ bad-column.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].mapping.cell[0].lower.columns[0]
          column 'city' is not in the row here — the source's columns are known and no earlier hook adds it; the hook would do nothing with it
          → Check the spelling, or add the column in an earlier hook
```

## One connector, one API

One connector is one API, whatever number of endpoints it exposes. Two endpoints of the same
API may page by different conventions:

```yaml
--8<-- "docs/connectors/rest/demo/20-two-endpoints.yaml"
```

```
$ lm run list --connector acme-saas
ID        CONNECTOR  ENDPOINT         STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
9d7a4883  acme-saas  link-partners    Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
3531807d  acme-saas  cursor-partners  Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
```

`lm run list` shows **one run per endpoint**. `stripe` and `drf-page` are two of the
[presets](rest/pagination.md#presets).

### Settings shared by every endpoint

`spec.defaults` holds what several endpoints would otherwise repeat. An endpoint that does not
write a key takes it from there; an endpoint that writes it keeps its own, whole:

```yaml
spec:
  defaults:
    incremental: { mode: watermark, param: updated_since, watermark_path: $.updated_at }
  endpoints:
    - id: partners               # runs incremental, from defaults
      ...
    - id: countries              # its own block replaces the default
      incremental: { mode: full }
      ...
```

| Key in `spec.defaults` | Taken by |
|---|---|
| `incremental` | every endpoint |
| `response_format` | REST endpoints (not GraphQL, not SOAP) |
| `variables` | GraphQL endpoints |
| `header`, `soap_action` | SOAP endpoints |

Only these five keys belong in `spec.defaults`. `lm validate` names any other. A mistake in a
default is reported once, at `spec.defaults.<key>`.

## Auth

| Kind | Config |
|---|---|
| `bearer` | `token_env: VAR_NAME` (preferred), or `token_value: "..."` (dev only); optional `scheme` (`Bearer`) |
| `basic` | `username` + `password_env: VAR_NAME` |
| `api-key` | `in: header\|query`, `name`, `value_env: VAR_NAME` |
| `oauth2` | `token_url`, `client_id`, `client_secret_env: VAR_NAME`; optional `scope`, `client_auth: basic\|post` — [below](#oauth2-client-credentials) |
| `session` | `login_url`, `username`, `password_env: VAR_NAME`; optional `prime_url`, `username_field`, `password_field`, `csrf_field` — [below](#a-form-login-session) |
| `none` | no credentials (the default when `auth:` is omitted) |

Every auth `*_env` field names an [`lm secret`](../cli/lm-reference.md#data-runs-answers) entry
first, then a variable of that name in the hub's environment. Set the value before `lm apply`:

```bash
lm secret set ACME_BASIC_PW     # prompts at the keyboard; what you type is not shown
```

- `scheme` is the word before a bearer token. An API on Django REST Framework's token
  authentication, NetBox for one, wants `Authorization: Token <key>` and refuses `Bearer`:
  `auth: { kind: bearer, scheme: Token, token_env: NETBOX_TOKEN }`. One word that starts with a letter,
  then letters, digits, `-` or `_`, at most 32 characters.
- `basic`, `api-key` and `session` have **no inline field**: the value comes from `password_env` /
  `value_env`, or not at all.
- `bearer`: when both are set, `token_env` wins and `token_value` is ignored, even if nothing
  resolves under `token_env`. Declare one or the other.

If the API writes your credential back into a response — an error that repeats the token it
received, for instance — lumnik hides it as `<redacted>` before showing that response anywhere
(`lm source rest test`, a failed run). The data you ingest is never rewritten.
[Credentials a source writes back](../security/echoed-credentials.md)

A credential that does not resolve is named before any run:

```
$ lm source rest test acme-badauth
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  GET     -       0      Basic password unavailable (env=ACME_NOT_SET_PW)
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

### OAuth2 client credentials

A SaaS that hands out a client id and a secret, and wants a short-lived token on every call:

```yaml
auth:
  kind: oauth2
  token_url: https://auth.saas.example/oauth/token
  client_id: lumnik-orders
  client_secret_env: SAAS_CLIENT_SECRET
  scope: orders.read            # optional, space-separated
  client_auth: basic            # optional: basic (the default) or post
```

- lumnik asks `token_url` for a token, keeps it as long as it is valid, and asks for a new one
  shortly before it expires.
- If the API refuses a token during a run (for example after rotating its keys), lumnik asks for
  a new one once and repeats the request.
- `client_auth: basic` works with almost every server; use `post` only if the server's
  documentation asks for it.

With the secret set and the grant accepted, the pre-flight reads the first page:

```
$ lm source rest test saas-orders
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
orders    GET     200     25     ok
```

Once the SaaS has revoked that secret, the same command and the next run both say so:

```
$ lm source rest test saas-orders
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
orders    GET     -       0      OAuth2 token refused by the authorization server: HTTP 401 invalid_client
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

What `lm source rest test` and a failed run say:

| Cause | Verdict |
|---|---|
| `client_secret_env` resolves to nothing | `OAuth2 client secret unavailable (env=SAAS_CLIENT_SECRET)` |
| The server refuses the grant | `OAuth2 token refused by the authorization server: HTTP 401 invalid_client` |
| `token_url` cannot be reached | `OAuth2 token request failed: host not resolved (UnknownHostException)` |
| The answer carries no token | `OAuth2 token response carries no access_token` |

`invalid_client` and similar codes come from the authorization server. The secret and the token
are never shown.

### A form login (session)

An older web application often has no API token: a user logs in through a form, and the server
keeps a session cookie (`JSESSIONID`). Spring MVC behind Spring Security works this way, and so does
Struts or any servlet application behind the container's own login (`j_security_check`).

Behind the container's login (Tomcat, WebLogic, …):

```yaml
auth:
  kind: session
  login_url: http://clinic.internal/petclinic/j_security_check
  prime_url: http://clinic.internal/petclinic/vets.json   # a protected page
  username: lumnik
  password_env: CLINIC_PASSWORD
  username_field: j_username
  password_field: j_password
```

Behind Spring Security's login form:

```yaml
auth:
  kind: session
  login_url: https://app.internal/login
  username: lumnik
  password_env: APP_PASSWORD
  csrf_field: _csrf             # the hidden input of the login page
```

- lumnik first reads `prime_url` (by default `login_url`) to get a session cookie. With
  `csrf_field`, it also reads the token from the page's hidden input of that name.
- Then it posts `username_field` and `password_field` (by default `username` and `password`) to
  `login_url`, and keeps the session cookie the login sets.
- The container's login wants a protected page as `prime_url`: it remembers that page, then
  accepts the form.
- lumnik keeps the session for the connector, run after run, while the hub runs. It logs in again when the
  application stops accepting it, and repeats the request once.
- The cookie is sent only to the origin of `login_url` (scheme, host, port), as a browser does. An
  endpoint on another host is refused. `prime_url` must be on the same origin.

With the password set and the login accepted, the pre-flight reads the first page:

```
$ lm source rest test clinic-vets
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
vets      GET     200     6      ok
```

With a password the application no longer accepts:

```
$ lm source rest test clinic-vets-old
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
vets      GET     -       0      session login set no new cookie (HTTP 200): the credentials or the form's field names are refused
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

A login that works sets a new session cookie, which is how lumnik tells it from a refused one: the
container answers a wrong password with its error page, not with an error status.

| Cause | Verdict |
|---|---|
| `password_env` resolves to nothing | `session password unavailable (env=CLINIC_PASSWORD)` |
| Wrong password or wrong field names | `session login set no new cookie (HTTP 200): the credentials or the form's field names are refused` |
| No hidden input named `csrf_field` | `session prime_url page has no input named '_csrf'` |
| The login answers an error | `session login answered HTTP 500` |
| Refused again right after a new login | `session refused: the source still answers HTTP 302 after a fresh login` |
| An endpoint on another origin | `session cookie stays with the login's origin (http://clinic.internal:80): a request to another origin is refused` |

The password and the session cookie are never shown.

Not covered: logins that need JavaScript, a second factor, or a redirect to SAML or CAS.

## An internal CA

An API served over HTTPS with a certificate your company's CA signed — an in-house service, a
reverse proxy with its own PKI, a Kubernetes API server — is refused at the handshake: lumnik
trusts the public CAs only. Name the CA in the connector:

```yaml
spec:
  base_url: https://kubernetes.default.svc
  tls:
    ca_secret: CLUSTER_CA        # an lm secret holding the CA's PEM certificate(s)
```

```bash
lm secret set CLUSTER_CA - < ca.crt
```

- **Only the CAs in that secret are trusted** for this connector — the public ones no longer are.
  Its runs, `lm source rest test` and `lm source rest probe` all share that trust.
- A secret may hold several PEM certificates (a root and an intermediate): each one is trusted.
- A certificate the trust refuses fails at once, never retried: `certificate refused
  (SSLHandshakeException)`. Another TLS problem reads `TLS failure`, and is retried like a
  network failure.
- **A secret nothing holds, or one that holds no certificate**, fails the run and `lm source rest
  test`. The message names the secret, never its content: `CA certificate unavailable
  (secret=CLUSTER_CA)`, `secret CLUSTER_CA holds no PEM certificate`.
- A `tls` block without `ca_secret` (missing or blank) is refused at `lm apply`. A declared trust
  never falls back to the public CAs.
- There is no switch to skip the check.
- An `oauth2` token request (`token_url`) still uses the public trust.

## The HTTP version

The hub uses HTTP/2 when the server offers it. Some services break their answer over HTTP/2.
Name the version in the connector:

```yaml
spec:
  base_url: https://wcf-orders:18443
  http_version: "1.1"        # "1.1" or "2"
```

- Absent, the hub negotiates: HTTP/2 when the server offers it, else HTTP/1.1.
- The value is a string. `1.1` unquoted is a number, and `lm validate` refuses it.
- It covers every endpoint, `lm source rest test` and `lm source rest probe`.
- When to set it: [a refusal that reads `I/O error`](soap.md#a-refusal-that-reads-io-error-tried-five-times).

## An answer in XML

Some APIs answer XML: PrestaShop's Webservice by default, public feeds, older applications. Declare it
on the endpoint with `response_format: xml`:

```yaml
endpoints:
  - id: products
    path: /dispatcher.php?url=products&display=[id,reference,price,name]
    method: GET
    response_format: xml
    pagination: { kind: prestashop, resource: products, limit: 50, response_path: $.products.product }
    target:
      kind: hybrid
      table: ext.shop_products
      unique_key: $.id
      promote:
        - { name: reference, path: $.reference, type: text }
        - { name: name, path: "$.name.language['#text']", type: text }
```

PrestaShop answers this:

```xml
<prestashop xmlns:xlink="http://www.w3.org/1999/xlink">
<products>
<product>
  <id><![CDATA[1]]></id>
  <reference><![CDATA[demo_1]]></reference>
  <name><language id="1" xlink:href="…/api/languages/1"><![CDATA[Hummingbird printed t-shirt]]></language></name>
</product>
…
```

The paths read it like this:

- The root element is left out: the list is `$.products.product`, not `$.prestashop.products.product`.
- An element with only text is its value: `$.reference` reads `demo_1`.
- Attributes go under `@`, the text beside them under `#text`: `$.name.language['#text']` reads the
  name, `$.name.language['@id']` its language.
- Namespace prefixes are dropped: `xlink:href` is `@href`.
- The list at `response_path` stays a list, even with one row.
- Values are text: `$.price` reads `"23.900000"`. Promote it with `type: decimal` to compare it as a number.

```
$ lm source rest test shop-products
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
products  GET     200     19     ok
```

Without `response_format: xml`, an XML answer is refused, never read as zero rows:

```
$ lm source rest test shop-products-json
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
products  GET     200     0      the response is not JSON (Content-Type text/xml;charset=utf-8) — rest-generic reads JSON; an endpoint answering XML declares response_format: xml
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

A document that declares a DTD or an entity is refused: lumnik never reads one. A body that is not
XML is refused by its `Content-Type`; the body itself is never shown.

## Pagination

Every endpoint declares how the source hands back its next page. Most APIs follow a named
convention, so the usual answer is one line:

```yaml
pagination: { kind: spring-page }        # Spring Data Page — seven fields under one word
```

Fifteen presets name the framework behind the API (Spring Data, Micronaut, Django REST,
FastAPI, NestJS, JSON:API, Sequelize, Stripe), the product (PrestaShop) or the protocol (OData v2
as SAP Gateway serves it, OData v4). When none fits, write one of seven strategies by hand:
`cursor`, `page`, `offset`, `link-header`, `body-link`, `relay`, and `none` for an answer that
holds the whole list.

**→ [Pagination](rest/pagination.md)** — the presets, the fields of each strategy, where the
cursor goes, and the page size on the strategies that send none.

## Request headers

Connector-level only — `spec.headers[]` goes out on **every** request:

```yaml
spec:
  headers:
    - { name: X-Api-Version, value: "2026-01" }       # static
    - { name: X-Client-Id,   value_env: CLIENT_ID }   # from lm secret or the environment
```

- Each entry needs `name` + **exactly one** of `value` / `value_env`.
- `value_env` reads like an auth `*_env` field: the `lm secret` entry first, then a variable of
  that name in the hub's environment.
- A `value_env` that resolves to nothing is an error, not a missing header: the run,
  `lm source rest test` and `lm source rest probe` stop before sending the request. The error
  names the header and the reference, never a value.
- Headers are applied **before** auth — an auth-injected header wins a name clash.
- Endpoint-level `headers` are refused:

```
$ lm apply -f bad-headers.yaml
✗ bad-headers.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].headers
          per-endpoint headers are not supported in this release; declare headers at spec.headers
          → Move the headers block up to spec.headers (connector level)

Fix errors above before running 'lm apply -f bad-headers.yaml'.
Error: validation failed: 1 error(s)
```

`lm source rest test` and `lm source rest probe` send the declared headers too. `/hdr/partners`
answers 400 without `X-Api-Version`; the `acme-witness` connector (manifest under
[The declared total](#the-declared-total-a-witness-on-the-count)) declares it:

```
$ lm source rest test acme-witness
ENDPOINT    METHOD  STATUS  ITEMS  VERDICT
incomplete  GET     200     500    ok
versioned   GET     200     500    ok
```

The same connector with `{ name: X-Api-Version, value_env: ACME_API_VERSION }`, before and after
the secret is stored:

```
$ lm source rest test acme-witness-env
ENDPOINT    METHOD  STATUS  ITEMS  VERDICT
incomplete  GET     -       0      Header X-Api-Version unavailable (env=ACME_API_VERSION)
versioned   GET     -       0      Header X-Api-Version unavailable (env=ACME_API_VERSION)
Error: 2 of 2 enabled endpoint(s) did not answer with readable data
$ lm secret set ACME_API_VERSION      # 2026-01
Created secret "ACME_API_VERSION"
$ lm source rest test acme-witness-env
ENDPOINT    METHOD  STATUS  ITEMS  VERDICT
incomplete  GET     200     500    ok
versioned   GET     200     500    ok
```

A run without it fails the same way: `✖ run failed: Header X-Api-Version unavailable (env=ACME_API_VERSION)`.

## Incremental sync

```yaml
incremental:
  mode: full              # re-fetch every page (default)
  # OR
  mode: watermark         # send the stored watermark as a query parameter,
  param: updated_since    #   e.g. ?updated_since=<watermark>
  watermark_path: $.updated_at   # new watermark = MAX of this JSONPath over the items read
  lookback: 10m           # optional — see "A change during a run" below
```


```yaml
--8<-- "docs/connectors/rest/demo/40-watermark.yaml"
```

Run it twice:

```
$ lm run list --connector acme-incremental
ID        CONNECTOR         ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED             EVALUATED
17c95cc8  acme-incremental  partners  Completed  2     2     0        0             2026-10-24 22:30  2026-10-24 22:30  yes
f9bc9c64  acme-incremental  partners  Completed  2400  2400  0        0             2026-10-24 22:30  2026-10-24 22:30  yes
```

Run 1 has no mark: it reads all 2 400 rows. Run 2 asks only for what changed since, with a small
margin ([below](#a-change-during-a-run)): 2 rows come back and replace their earlier version. The
table still holds 2 400 rows.

- The mark advances only when the run completes, never page by page.
- `lm endpoint reset-cursor acme-incremental/partners` clears it; the next run is a full read.
  The command prints nothing and exits 0.
- Point `watermark_path` at a column the source updates on every change. Otherwise every run
  re-reads everything.
- Best: a date and time with a time zone, or a number that only grows.

### A change during a run

An order that changes while a run is reading could be missed by the next run. So each run asks
from a little before the last mark. The few orders read twice are simply updated again.

- By default, the margin is the length of the run that set the mark, plus one minute.
- `lookback: 10m` chooses that margin yourself; `lookback: 0s` turns it off.
- It works when `updated_at` is a date and time with a time zone. With a change number or a date
  alone, the mark is sent as it is, and such a change can still be missed.

## Watch the rows you read

A `kind: Process` or a `kind: Workflow` can name the connector's `ext.*` table. A Process needs the
table to exist first: apply it after the first run completes. A Workflow may be declared before.

```yaml
spec:
  on:
    event: data.row.changed
    table: ext.acme_partners
```

A new row fires the process. So does a row whose content changed at the source. A re-read that
changes nothing fires nothing.

- `when` and `{col}` name the promoted columns.
- Rows that existed before the Process was applied are not evaluated.
- A Workflow orders a key's versions by `indexed_at`, the moment the row last changed.
- `spec.check.trigger: ingestion` is refused on an `ext.*` table. The check at apply time still
  runs.

See [Processes](../apps/processes.md#processes-on-rest-tables) and [Workflows](../apps/workflows.md).

## Storage layout

Each endpoint's table is created on the first run. Besides the promoted columns, it holds:

| Column | Holds |
|---|---|
| `external_id` | the `unique_key`: a re-run replaces the row instead of adding one |
| `data` | the whole payload, as JSON |

A re-run rewrites only the rows that changed, yet `OUT` still counts every row read and
`SKIPPED` stays `0`. To check that nothing duplicated, count the table's rows.

A new `promote` entry adds its column on the next run — see
[Add a typed column later](#add-a-typed-column-later).

!!! warning "Deleting a REST connector deletes its data"
    `lm connector delete` removes the connector's rows from its `ext.*` tables (a table goes
    with the last connector writing into it), and every RAG chunk it produced. To empty one
    endpoint's rows and keep the connector, see [Empty one endpoint's rows](#empty-one-endpoints-rows).

## When the source misbehaves

The connector retries what is worth retrying. It never reads an error body as data. Where the
source publishes a count, it checks the run against it.

### Retry, page cap, rate limit

| Guard | Behaviour |
|---|---|
| Retry | `408`, `429`, `500`, `502`, `503`, `504`, timeouts and network errors are tried again: 5 attempts in all, with growing pauses, or the wait the source's `Retry-After` header asks. Then the run fails. Other statuses are not retried |
| Page cap | **10 000 pages** per endpoint per run. Reached before the source's last page, the run fails: `reached the page cap (10000 pages) before the source's last page`. The rows already read stay; the run is not called complete |
| Rate limit | `default_rate_limit` (default `100/s`): `count/window`, window `s`, `m` or `h` with an optional integer (`100/s`, `100/5m`, `1000/h`). One bucket per connector, shared by all its endpoints — set it to the strictest endpoint's limit |

### An outage fails the run

`/down/partners` answers 500 to everything; `/flaky/partners` answers 429 then 503 before
serving each page:

```yaml
--8<-- "docs/connectors/rest/demo/60-source-down.yaml"
```

```
$ lm run list --connector acme-outage
ID        CONNECTOR    ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
b3e0c1db  acme-outage  flaky     Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
a05e8be8  acme-outage  down      Failed     0     0     0        0             2026-10-19 01:40  2026-10-19 01:40

$ lm run get a05e8be8
ID        CONNECTOR    ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
a05e8be8  acme-outage  down      Failed  0   0    0        0             2026-10-19 01:40  2026-10-19 01:40
✖ run failed: upstream returned HTTP 500 (transient) — refusing to ingest its body as data: {"error": "this endpoint is down and stays down"}
```

A final non-2xx fails the run: it is never reported as an empty sync. A request that succeeds
on retry does not fail it: the flaky endpoint completes with all 2 400 rows.

The pre-flight sends **one** request without retry, so a flaky source shows red there:

```
$ lm source rest test acme-outage
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
flaky     GET     429     0      HTTP 429
down      GET     500     0      HTTP 500
Error: 2 of 2 enabled endpoint(s) did not answer with readable data
```

Every enabled endpoint is tried; the command exits non-zero if any did not answer.

### The declared total — a witness on the count

A run that loses rows can still end green. Where the source publishes its row count,
`total_path` checks the run against it. `/liar/partners` serves 2 300 of its 2 400 partners and
still declares `"total": 2400`:

```yaml
--8<-- "docs/connectors/rest/demo/50-total-witness.yaml"
```

```
$ lm run list --connector acme-witness
ID        CONNECTOR     ENDPOINT    STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
03b05ee0  acme-witness  versioned   Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
a368b49d  acme-witness  incomplete  Failed     2000  2000  0        0             2026-10-19 01:40  2026-10-19 01:40

$ lm run get a368b49d
ID        CONNECTOR     ENDPOINT    STATUS  IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
a368b49d  acme-witness  incomplete  Failed  2000  2000  0        0             2026-10-19 01:40  2026-10-19 01:40
✖ run failed: Endpoint 'incomplete': the source declared 2400 rows but this run returned 2300 — refusing to report a partial extract as a complete one
```

- The run fails. The rows already written stay: 2 000 here. The next successful run completes
  them.
- `total_path` is optional on every strategy. A `soap` endpoint ignores it.
- On `offset`, it also stops the read once every row is in.
- Most presets set it for you, e.g. `drf-page`, `drf-limit-offset` and `sequelize` set
  `total_path: $.count`. The [presets table](rest/pagination.md#presets) names each one's total.
- Point `total_path` at an exact **row** count: not a page count, not an estimate.
- The check does not run on an incremental endpoint.

**A total that changes during the read.** A row deleted on a page already read moves every later
row up one place. One row then sits on no page, and the read ends without it. So when the total
changes between two pages, the run reads the list again from page 1:

- The rows read twice are written once (the `unique_key` upsert). `IN` counts both reads.
- If the total changes again during the second read, the run fails.

Paperless-ngx, read 10 correspondents per page. During the first run, one correspondent is deleted:
60 rows read, then the whole list of 100. During the second, new correspondents keep arriving:

```
$ lm run get cbf6e5fd
ID        CONNECTOR          ENDPOINT        STATUS     IN   OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
cbf6e5fd  paperless-archive  correspondents  Completed  160  160  0        0             2026-11-03 14:10  2026-11-03 14:11

$ lm run get 1abfa915
ID        CONNECTOR          ENDPOINT        STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
1abfa915  paperless-archive  correspondents  Failed  20  20   0        0             2026-11-03 14:11  2026-11-03 14:11
✖ run failed: Endpoint 'correspondents': the list changed while it was read, and again when read a second time (the source declared 103 rows, then 104) — rows may be on no page; refusing to report a partial extract as a complete one (run it when the source is quieter)
```

**A count in a response header.** Gitea, GitLab or WordPress send it as a header, beside a body
that is just the list: name the header with `total_header` instead of `total_path`.

```yaml
pagination: { kind: link-header, response_path: $, total_header: X-Total-Count }
```

- The header's name is matched in any case.
- A response without the header checks nothing.
- Its value is a whole number from 0, or the total of a range: `Content-Range: items 0-19/57`
  reads 57.
- Anything else is refused, a range total of `*` or a `bytes` range included:
  `pagination.total_header must read a row count: a whole number from 0, or the total of a range
  (items 0-19/57)`. `lm source rest test` reads the header the way a run does.
- `total_path` and `total_header` together are refused at `lm validate`: one witness per endpoint.
- It checks the count only: the pages are read as without it. On `offset`, the read ends on its
  first short page: one more request when the total is a multiple of the limit.

## CLI

```bash
lm apply -f <file.yaml>
lm validate -f <file.yaml>
lm connector run <connector>
lm endpoint enable|disable|reset-cursor|purge <connector>/<endpoint>
lm source rest test <connector>                      # pre-flight every enabled endpoint, no write
lm source rest probe <connector> --endpoint <path>   # sample one path, draft its endpoint block
lm source rest from-openapi <url> --name N [--scope S] -o FILE
```

## TUI

- `:sources` — the `KIND` column shows rest/jdbc/csv; Enter on a REST connector drills into
  `:endpoints`
- `:endpoints` — NAME/METHOD/PATH/SCHEDULE/LAST_RUN/STATUS/ENABLED; Enter → `:runs` filtered

Full reference: [CLI & TUI](../cli/lm-reference.md).

## How to…

### Check a connector before running it

```bash
lm source rest test <connector>
```

**Result:** for each enabled endpoint, the hub sends the first request a run would send (same
method, path, headers, auth and pagination) and reports what came back. Nothing is written.

- `200`, `0` items and `ok`: the response arrived and `response_path` reads a list, which is empty.
  When `response_path` reads nothing, or reads something that is not a list, the verdict is the
  message a run would fail with (`pagination.response_path reads nothing on the first page`).
- Disabled endpoints are skipped and counted on stderr.
- `-o json` adds the URL called (host and path; the query string is not shown) and an excerpt
  of the body:

```
$ lm endpoint disable acme-saas/link-partners
$ lm source rest test acme-saas -o json
note: skipped 1 disabled endpoint(s)
[
  {
    "endpoint": "cursor-partners",
    "method": "GET",
    "url": "http://host.docker.internal:8099/v1/partners",
    "status": 200,
    "ok": true,
    "items": 500,
    "detail": "",
    "sample": "{\"data\": [{\"id\": 1000, \"name\": \"Hydraulique Lyon 1000\", \"city\": \"Lyon\", \"country\": \"FR\", \"credit_limit\": 500, \"updated_at\": \"2026-01-01T00:00:00Z\"}, {\"id\": 1001, \"name\": \"Roulements Bruxelles 1001\", \"…"
  }
]
$ lm endpoint enable acme-saas/link-partners
```

### Draft an endpoint from a live path

`probe` samples **one path on an existing connector** and prints the endpoint block that would
ingest it. The base URL and credential come from the connector:

```
$ lm source rest probe acme-partners --endpoint /spring/partners
# Drafted by lm source rest probe — review before applying
- id: spring-partners
  method: GET
  path: /spring/partners
  pagination: { kind: page, response_path: $.content }
  target:
    kind: hybrid
    table: ext.acme_partners_spring_partners
    unique_key: $.id
    promote:
      - { name: id_2, path: $.id, type: bigint }
      - { name: name, path: $.name, type: text }
      - { name: city, path: $.city, type: text }
      - { name: country, path: $.country, type: text }
      - { name: credit_limit, path: $.credit_limit, type: bigint }
      - { name: updated_at, path: $.updated_at, type: text }
```

**Result:** an endpoint **fragment** — paste it under `spec.endpoints:`, review it, then
`lm apply`.

- The pagination kind is a guess from the response envelope. An unknown envelope gives
  `kind: unknown` for you to fill in.
- `response_path` says where the list sits in the answer. The draft carries it whatever the kind.
  An answer that is itself one record has no list, so the draft has none.
- A list found with no paging signal is drafted as `kind: none`, with its `response_path` and a
  comment that gives the number of items read, such as
  `# 6 items, no paging signal in the answer: if the source pages, declare how`. A round count
  such as 20 or 50 is often one page: declare the pagination.
- The promote list covers the scalar fields of the first record only.
- A field named like a column the table already has (`id`, `data`, `row_hash`, …) is drafted under
  another name, `id_2`: the `path` still reads the field by its own key.

A source that pages through a `Link` header needs no other setting, so its draft applies as it is:

```
$ lm source rest probe acme-header --endpoint '/link/partners?limit=500'
# Drafted by lm source rest probe — review before applying
- id: link-partners-limit-500
  method: GET
  path: /link/partners?limit=500
  pagination: { kind: link-header, response_path: $.data }
  target:
    kind: hybrid
    table: ext.acme_header_link_partners_limit_500
    unique_key: $.id
    promote:
      - { name: id_2, path: $.id, type: bigint }
      - { name: name, path: $.name, type: text }
      - { name: city, path: $.city, type: text }
      - { name: country, path: $.country, type: text }
      - { name: credit_limit, path: $.credit_limit, type: bigint }
      - { name: updated_at, path: $.updated_at, type: text }
```

An upstream error is reported instead of a draft:

```
$ lm source rest probe acme-partners --endpoint /open/nope
Error: API error 502: upstream returned HTTP 404 (permanent) — refusing to ingest its body as data: {"error": "unknown resource", "known": ["down", "drf", "flaky", "hdr", "liar", "link", "offset", "open", "spring", "v1"], "resource": "/open/nope"}
```

A connector that is not `rest-generic` is refused by name.

### Generate a manifest from an OpenAPI spec

The hub reads the spec and writes a draft manifest. The [demo API](rest/demo-api.md) serves
one at `/openapi.json`:

```bash
lm source rest from-openapi http://localhost:8099/openapi.json \
    --name acme-from-openapi -o acme-from-openapi.yaml
```

The hub fetches the URL itself. From a hub in Docker, use
`http://host.docker.internal:8099/openapi.json`. The draft's `base_url` is the spec's
`servers` entry; the demo puts there the address you called.

**Result:** one endpoint per `GET` path of the spec. The draft writes only what the spec says.
A path it cannot page correctly stays in the file, commented out:

```yaml
apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata:
  name: acme-from-openapi
  connector_type: rest-generic
spec:
  base_url: "http://localhost:8099"
  auth: { kind: bearer, token_env: ACME_FROM_OPENAPI_API_KEY }
  endpoints:
    # auth: this path asks for no credentials, not a bearer token — move it to a connector of its own.
    # pagination: not drafted — limit has no default and no maximum, so the page size is unknown.
    # Declare an offset pagination with a limit the source serves whole, then uncomment (https://docs.lumnik.io/connectors/rest/pagination/).
    # - id: open-partners
    #   method: GET
    #   path: "/open/partners"
    #   incremental: { mode: full }
    #   target: { kind: hybrid, table: ext.acme_from_openapi_open_partners, unique_key: $.id, promote: [] }
    # auth: this path asks for http basic, not a bearer token — move it to a connector of its own.
    # pagination: not drafted — page has no default and no minimum, so where pages start is unknown.
    # Declare a page pagination with its start_page, then uncomment (https://docs.lumnik.io/connectors/rest/pagination/).
    # - id: spring-partners
    #   method: GET
    #   path: "/spring/partners"
    #   incremental: { mode: full }
    #   target: { kind: hybrid, table: ext.acme_from_openapi_spring_partners, unique_key: $.id, promote: [] }
    - id: v1-partners
      method: GET
      path: "/v1/partners"
      pagination: { kind: cursor, cursor_param: starting_after, cursor_path: "$.data[-1:].id", has_more_path: "$.has_more", response_path: "$.data" }
      incremental: { mode: full }
      target: { kind: hybrid, table: ext.acme_from_openapi_v1_partners, unique_key: $.id, promote: [] }
```

The draft passes `lm validate` when at least one endpoint is not commented out. Review three
things before you apply it:

- **Auth.** It comes from the spec's security schemes: http basic, bearer or an API key, or
  none. Auth is one per connector, so the draft keeps the one most paths ask for. A path that
  asks for another is marked `# auth:`: give it a connector of its own. Create the secret the
  draft names (`ACME_FROM_OPENAPI_API_KEY` here) with `lm secret set`. A basic block also needs a
  real `username` in place of `CHANGE_ME`.
- **Pagination.** It is drafted only when the parameters and the answer both say how pages work:
  a Stripe cursor, `offset` + `limit` with a declared limit, or `page` with a declared first
  number. Otherwise the endpoint is commented out under the key to declare. A guess here could
  skip a page and still end clean. In the demo, `/open/partners` takes
  `{ kind: offset, offset_param: offset, limit_param: limit, limit: 100, response_path: $.data }`
  and `/spring/partners` takes `{ kind: spring-page }`.
- **Target.** `promote` is empty: every row lands as JSONB only.

A URL that holds no spec is refused:

```
$ lm source rest from-openapi http://127.0.0.1:8099/spring/partners --name x --scope billing -o x.yaml
Error: API error 502: no OpenAPI spec could be read at http://127.0.0.1:8099/spring/partners — unreachable, not a spec, or not parseable
```

### Reset a stuck cursor

```bash
lm endpoint reset-cursor <connector>/<endpoint>
```

**Result:** the stored watermark is cleared and the next `lm connector run` is a full read.
The command prints nothing.

### Empty one endpoint's rows

`lm endpoint purge` deletes the rows one endpoint has landed and keeps the connector, its other
endpoints and its run history. `--dry-run` only counts:

```
$ lm endpoint purge acme-incremental/partners --dry-run
Would purge 2400 rows (dry run, nothing deleted)
$ lm endpoint purge acme-incremental/partners
Type 'purge' to delete 2400 rows: purge
Purged 2400 rows
$ lm endpoint purge acme-incremental/partners
Nothing to purge: this endpoint has no rows
```

- Every `rest-generic` endpoint can be purged. A `jdbc-generic`, `csv-file` or `kafka` endpoint
  is refused — see [Emptying one endpoint on its own](jdbc.md#emptying-one-endpoint-on-its-own).
- `--force` skips the question.
- Add `lm endpoint reset-cursor` to re-read the endpoint from scratch on the next run.
- In `lm ui`, `Del` on `:endpoints` does the same, with the same count and question.

### Add a typed column later

Add an entry to the endpoint's `target.promote` list in your copy of `10-quickstart.yaml`
(here `acme-partners.yaml`) and re-apply:

```yaml
promote:
  - { name: city,    path: $.city,    type: text }
  - { name: country, path: $.country, type: text }   # new column
```

**Result:** `lm apply` changes nothing in the database; the **next run** adds the column and
fills it as rows are read again.

```
$ lm apply -f acme-partners.yaml
Applied connector "acme-partners" — 1 endpoint
$ lm connector run acme-partners
run scheduled: b9a1bf1a-f9dc-403c-a2d4-6cdab8fb7f79
$ lm run list --connector acme-partners
ID        CONNECTOR      ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
b9a1bf1a  acme-partners  partners  Completed  2400  2400  0        0             2026-10-19 07:25  2026-10-19 07:25
9af1c00d  acme-partners  partners  Completed  2400  2400  0        0             2026-10-19 07:25  2026-10-19 07:25
```

```
lumnik=> SELECT count(*) FILTER (WHERE country IS NOT NULL) AS with_country, count(*)
lumnik->   FROM ext.acme_partners;
 with_country | count 
--------------+-------
         2400 |  2400
(1 row)
```

A `full` endpoint fills every row on that run; a `watermark` endpoint fills only the rows that
change, unless you `lm endpoint reset-cursor` first.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Not sure the credential is valid | — | `lm source rest test <connector>` — one request per enabled endpoint, nothing written; an unresolved secret is named in `VERDICT` |
| Run failed on `pagination.response_path reads nothing on the first page` | the first response arrived, but nothing matched `response_path` | `lm source rest test <connector>` shows the same message as its verdict; check `response_path` against the body, or use the source's preset |
| Run failed on `pagination.response_path must read a list` | `response_path` points at an object or a value, not at the rows | point it at the list itself |
| Run completed, `IN` is 0 | `response_path` reads a list, and the list is empty | nothing to fix: the source holds no rows (for an incremental read, none changed) |
| Run failed on `the source declared N rows` | `total_path` points at a number the run did not match | check the source; if `total_path` points at a page count or an estimate, see [The declared total](#the-declared-total-a-witness-on-the-count) |
| Run failed on `the list changed while it was read, and again` | rows are added or deleted faster than the list is read | run it at a quieter hour (`window:`), or raise the page size so the read is shorter |
| Run failed on `the source answered the same page twice in a row` | the source ignores the paging parameter and answers page one again — PrestaShop, for one, ignores `offset=` | check the pagination's parameter names against the API's documentation, or use the source's preset |
| Run failed on `reached the page cap` | 10 000 pages and the source still has more — often a page size too small, or a source that never says it is done | raise the page size; if the source really has more, split the endpoint (a filter in `path:`) |
| `429 Too Many Requests` from the API | `default_rate_limit` exceeds what the API tolerates | lower it (`100/s` → `50/s`); it is one bucket for the whole connector |
| Sync stops picking up new rows | the stored watermark is stale or points at a column the source does not touch | `lm endpoint reset-cursor <connector>/<endpoint>` → the next run is a full read |
| A run reads far more pages than expected | `cursor`, `body-link` and `link-header` send no size parameter | put the size in the endpoint's `path:` — see [the page-size warning](rest/pagination.md#strategies) |
| `lm apply` refused with an unknown transform | a `promote` entry's `transform:` is not one of the supported values | see [Transform a promoted value](#transform-a-promoted-value) |
| `column 'x' is not in the row here` at `…mapping…` | a hook reads a field the endpoint does not promote | promote it: a hook sees the promoted columns only |
| `path is required … this endpoint has none` | a `promote` entry without `path`, and no `mapping` to fill it | give the entry a `path`, or the endpoint a `mapping` |
| Run failed on `the mapping writes column 'x', which no promote entry declares` | a script writes a column the table does not have | add `{ name: x, type: … }` to `promote`, without `path` |
| A process on an `ext.*` table is refused with `is not in the '…' scope` | no run has created the table yet, so the scope's card does not carry it | run the connector once and let it complete (the hub refreshes the card); if the table is still missing, `lm source rediscover --scope <tag>`, then apply |
| Need to wipe an endpoint's rows | — | `lm endpoint purge <connector>/<endpoint>` — see [Empty one endpoint's rows](#empty-one-endpoints-rows) |

## See also

- [Pagination](rest/pagination.md) — presets, strategies, cursor binding
- [The demo API](rest/demo-api.md) — the source every screen here reads
- [Overview](index.md)
- [GraphQL](graphql.md) — the GraphQL request shape on this engine
- [SOAP](soap.md) — the SOAP request shape on this engine
- [Transformers](transformers.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
