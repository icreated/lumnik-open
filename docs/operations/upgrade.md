# Upgrading the hub

> Migrations run forward-only at boot. The pre-upgrade backup is the only rollback.

## The short version

1. **Take a backup** — [backup-restore.md](backup-restore.md). The backup is the rollback.
2. Note the current schema versions:

    ```
    $ lm migration status
    SCHEMA     VERSION  PENDING  LAST APPLIED
    core       83       0        2026-10-25 07:04
    identity   18       0        2026-10-24 21:55
    rag        6        0        2026-10-24 21:55
    search_pg  3        0        2026-10-25 00:13
    ```

    `search_pg` appears only on a hub built with Search.

3. Point at the new image and restart:
    - **selfhost:** set `LUMNIK_VERSION` in `.env` (or leave it unset to track `main`), then
      `deploy/selfhost/up.sh`. It keeps the overlays the stack was started with (TLS proxy, Search).
      A stack with Search is rebuilt from source, not pulled: `git pull` first, as in
      [Running from source](#running-from-source).

    - **Helm (cloud edition):** bump `hub.image.tag`, then `helm upgrade`.
4. The hub migrates its database at boot, then reports healthy:

    | Path | Boot budget | On timeout |
    |---|---|---|
    | selfhost compose | ~210 s (90 s grace + 12 × 10 s) | the container shows `unhealthy`; it is not restarted |
    | Helm (cloud edition) | ~150 s (startup probe) | Kubernetes restarts the pod |

    Follow the boot with `./deploy/selfhost/logs.sh hub`.
5. Run `lm migration status` again: a higher `VERSION` and `PENDING 0` on every schema means the
   migrations are done.
6. If an entity declares [`resolution:`](../entities-resolution.md), apply it again with the same
   manifest, then run `lm entity resolve NAME`.

## Turning on the run queue

`LUMNIK_RUN_QUEUE_ENABLED=true` (Helm: `runQueue.enabled`) makes manual connector runs and
on-demand export runs wait in status `Queued` ([connectors](../connectors/index.md),
[exports](../apps/exports.md#runs-can-queue)).

| Setting | Default | Effect |
|---|---|---|
| `LUMNIK_RUN_QUEUE_ENABLED` | `false` | queue manual and on-demand runs |
| `LUMNIK_RUN_QUEUE_MAX_AGE` | `1h` | a run still queued after this ends: `Cancelled` (connector), `Failed` (export) |
| `LUMNIK_RUN_QUEUE_POLL_INTERVAL` | `5s` | how often a waiting run is picked up |
| `LUMNIK_CONNECTOR_MAX_CONCURRENT_SCHEDULED_RUNS` | `4` | how many endpoint runs one hub drives at once. Each takes up to two connections of the `admin` pool: raise `quarkus.datasource."admin".jdbc.max-size` with it |

Set them in `.env`, then run `./deploy/selfhost/up.sh` (Helm: `runQueue.*`, and `hub.extraEnv`
for the last one). The pool size is not a `.env` setting: it
goes in an override file, as in [Turning up the volume](../recipes/logs.md#turning-up-the-volume).

The run queue is global to the deployment: upgrade **every** hub replica to the same release first,
then turn it on. Turning it off again:

- runs already queued still start, or end once they pass `LUMNIK_RUN_QUEUE_MAX_AGE`;
- new manual and on-demand runs start at once, except where a run is still queued: there a new
  connector run is born `Cancelled` and a new export run answers `409`, until the queued one drains;
- nothing is left stuck.

## Release notes that need a gesture

Some releases change what existing data means. Each section below says what you see after
upgrading and what to do.

### S3 connects without a proxy { #s3-connects-without-a-proxy }

**Who is concerned:** a hub that reaches its S3 stores through a proxy, set with
`-Dhttps.proxyHost` or `-Dhttp.proxyHost`.

**What you see:** S3 sources and exports connect to the store directly. Where the network allows
only the proxy, their runs fail with a connection timeout.

**The gesture:** open the route from the hub to the store. The hub checks the store's address at
every connection, so it cannot go through a proxy that picks the address itself.

**Also concerned:** a hub with `lumnik.outbound.allow-private=false` (the Helm default) that
reaches AWS S3 through a VPC endpoint with private DNS. The bucket's name resolves to a private
address, and its runs fail: `the host resolves to a private address, which this hub refuses`.
Reach S3 by its public address: drop the private DNS for S3, or route the hub to the public
endpoint.

`lumnik.outbound.allow-private=true` (Helm: `--set outbound.allowPrivate=true`) also works, but it
is a decision for the whole hub. It opens the private networks to every address a tenant
chooses: every connector, export and webhook, not S3 only.

### S3 without `endpoint` goes to AWS { #s3-without-endpoint-goes-to-aws }

**Who is concerned:** a hub that sends S3 sources to MinIO, or to another store, through
`AWS_ENDPOINT_URL`, `AWS_ENDPOINT_URL_S3` or a profile's `endpoint_url`.

**What you see:** an S3 source or export with no `endpoint:` goes to AWS. Its runs fail, because
AWS does not know the key.

**The gesture, before upgrading:** write the store's address in each S3 transport and export:

```yaml
transport:
  kind: s3
  endpoint: http://minio:9000
```

### A JDBC URL takes only the listed parameters { #a-jdbc-url-takes-only-the-listed-parameters }

A JDBC URL now takes only the parameters listed in
[Where the hub connects](../security/outbound-addresses.md#a-jdbc-url). Any other is refused by
name. The source does not connect, and its runs fail:

```
the hub cannot tell where this URL connects: it sets sslcert, which the hub does not take
```

- **PostgreSQL:** `sslcert`, `sslkey`, `sslrootcert`, `sslfactory` and the Kerberos settings name
  the hub's own files or tickets. `sslmode=verify-ca`, `verify-full` and `ssl=true` alone are
  refused too: they read a CA from the hub's files.
- **Oracle:** TCPS is refused for now: the driver would use the hub's own certificates. Connect
  over TCP, or wait for certificates from `lm secret`. The SSL settings are refused too, even over
  TCP: `ssl_server_dn_match`, `ssl_server_cert_dn`, and `SECURITY` in a descriptor. Remove them.
  A wallet, a token or a proxy is refused, in the URL or in a descriptor. So is a
  descriptor keyword outside the list, and a `?` inside a descriptor.
- **The hub presents no client certificate any more.** A server that requires one refuses the
  connection.
- **`lm validate` and `lm apply` refuse such a URL** with the same reason.

**The gesture:** remove the refused parameter. For an encrypted PostgreSQL connection, write
`sslmode=require`. The traffic stays encrypted, but the hub no longer checks the server's
certificate: someone on the network path could pose as the database. If you relied on
`verify-full`, keep the source on a network you trust until the hub can take a CA of yours.

### A connector's inline credentials are no longer served back { #inline-credentials-are-no-longer-served-back }

A manifest may write a credential in plain text instead of naming a secret: jdbc's `password`, a
`password=` in its URL, REST's `auth.token_value`, a credential header's `value`, the password in a
`base_url`. The hub stores it in the connector's config, and returned it whole to every read of
the connector or its endpoints. It now returns `<redacted>` in its place, `%3Credacted%3E` inside a
URL or an endpoint's path.

- **Sending a config back as you read it keeps the stored value**: `<redacted>` stands for it.
- **A new value replaces it**, as before.
- **`<redacted>` where nothing is stored**, or inside a value whose rest changed, is refused:
  write the value itself.
- **A changed address takes no stored credential**: change `base_url`, `url`, `token_url` or a
  host, and write the credentials again with it.

**The gesture:** none. Prefer naming a secret (`password_env`, `token_env`, `value_env`), stored
with `lm secret set`: then nothing of the credential is in the connector at all.

### Health and metrics moved to port 9000 { #health-and-metrics-moved-to-port-9000 }

The hub answered `/q/health`, `/q/metrics`, `/q/openapi` and `/q/swagger-ui` on 8080, without a
token, and the TLS proxy forwarded them. They now answer on the hub's management port, 9000, which
nothing routes.

- **On 8080 they answer 404**, and so does the TLS proxy, for any `/q/*`.
- **The selfhost healthcheck and the Helm probes** read port 9000 by themselves.
- **Swagger UI** runs only on a hub started in dev mode.
- **`./deploy/selfhost/health.sh`** prints the health, as `curl localhost:8080/q/health` did.

**The gesture:**
- **A Prometheus job** scraping `<hub>:8080/q/metrics`: point it at `hub:9000/q/metrics` from inside
  the hub's network ([observability](observability.md#scraping-with-prometheus)).
- **A monitor** reading `/q/health` from outside: read it from inside instead, or use
  `GET /api/platform/health` with an `lm_integrator` token.
- **Postman's import by URL**: save the spec from inside the container
  ([Test the API](../recipes/test-the-api.md#import-the-whole-api)).
- **Do not publish 9000**, and do not give it a Service or a route.

### `lm` sends no token over plain http to another machine { #lm-sends-no-token-over-plain-http-to-another-machine }

`lm` now refuses to send a token, a refresh token or a login over `http://` to a host that is not
this machine. `localhost`, `127.0.0.1` and names under `.localhost` or `.test` are unchanged.

```text
Error: refusing to send your token to http://hub.example.com:8080 in clear text: use https (the TLS proxy), or allow this context: lm config set-context prod --allow-insecure-http
```

**The gesture:** put the hub behind https ([the TLS proxy](../deploy.md#one-https-origin-the-tls-proxy)), then
point the context at it. On a network you trust, allow the context instead:
`lm config set-context prod --allow-insecure-http`. `--allow-insecure-http=false` takes it back.
`LUMNIK_ALLOW_HTTP=1` allows a single run.

`lm login` now checks the issuer that Keycloak names. Behind the TLS proxy, a context on
`http://localhost:8180/realms/lumnik` is refused there. Point it at the proxy's issuer:
`lm config set-context <name> --oidc-issuer https://erp.example.com/realms/lumnik`.

### Two tenants on one table name see only their own columns { #two-tenants-on-one-table-name-see-only-their-own-columns }

Two tenants that ingest the same source write one hub table. Now each one sees only the columns its own data brought.

- **In Ask,** "show everything" shows your columns only. A column you did not bring is answered as a column that
  does not exist.
- **In an entity,** naming another tenant's column is refused, as a column that does not exist.
- **A few rare question shapes** are refused in Ask: `I won't run this: the query names '<table>' in a way I cannot
  check against this scope's columns. Ask again, naming the columns you want.` Ask again,
  naming the columns you want.
- **Tables ingested before this release** may still show another tenant's columns to a tenant. The hub cannot tell
  them apart from the tenant's own, so it leaves them. No command sets a tenant's columns by hand yet.

**The gesture:** none. To keep two tenants fully apart, give each connector its own target table
([Two tenants, one table name](../security/rls.md#two-tenants-one-table-name)).

### An entity, a view or an app belongs to the tenant that applied it { #an-entity-a-view-or-an-app-belongs-to-the-tenant-that-applied-it }

Each tenant sees only the entities, views and apps applied for it.

- **One tenant:** nothing changes. Your entities, views and apps stay yours.
- **Several tenants:** what you applied before is shared. Every tenant sees it. Change it as `hubadmin`.
- **Always shared:** the built-in apps, `clients` and `stock`.
- **To share one with every tenant:** apply it as `hubadmin`. That needs `lm_superadmin`, on top of the command's
  usual role. The bundled `hubadmin` has `lm_superadmin`; give it `lm_integrator` to apply a view or an app.
- **`lm rag reindex` as `hubadmin`** also needs `lm_superadmin`.
- **A name is unique on the hub.** If another tenant uses it, choose another:

  ```text
  409 {"error":"this name is already taken on this hub: choose another"}
  ```

- **Another tenant's entity, view or app** answers `404`. Ask does not see its entities.
- **The default app:** a tenant can have its own, with `default: true`. Without one, it opens the shared default.

**The gesture:** with one tenant, none. With several, change a shared entity, view or app as `hubadmin`.

### On a hub with several tenants, `lm_superadmin` writes entities, views and apps { #on-a-hub-with-several-tenants-lm-superadmin-writes-entities-views-and-apps }

With several tenants, only `lm_superadmin` applies and deletes entities, views and apps.

- **One tenant: nothing changes.** `lm_admin` and `lm_integrator` apply and delete as before.
- **Several tenants: only `lm_superadmin` writes them.** That is `lm entity apply` and `delete`, `lm view apply` and
  `delete`, `lm app apply` and `delete`, and `lm rag reindex`. Anyone else gets:

  ```text
  403 {"error":"deployment-wide: this hub serves several tenants, and entities, views, apps and the platform corpus are written by lm_superadmin only"}
  ```

- **When it starts:** with the first tenant you create besides the bundled ones, or at once with
  `LUMNIK_TENANCY=multi`, the Helm default ([the rule](../security/role-management.md#on-a-hub-with-several-tenants)).

**The gesture:** with several tenants, give `lm_superadmin` to the integrator who applies them, in the tenant they
serve, beside the roles they hold ([Make someone superadmin](../security/identity-admin.md#make-someone-admin-integrator-superadmin)).
A view is checked against that tenant's data, so the bundled `hubadmin`, in its own tenant, is not the account for it.
With your own IdP, create the role and grant it.

### The TLS proxy serves the lumnik realm only { #the-tls-proxy-serves-the-lumnik-realm-only }

The TLS proxy passed every Keycloak realm, `master` included: anyone reaching it could try the
admin's password on `master`'s token endpoint. It now passes the `lumnik` realm only (`/realms/lumnik` and
`/realms/lumnik/*`); any other realm answers `404`.

- **Nothing changes for users, `lm` or the PWA:** they sign in on `lumnik`.
- **The Keycloak admin console** stays on `http://localhost:8180`, or an SSH tunnel to it, as
  before.

**The gesture:** re-run `./deploy/selfhost/up.sh`. It reloads the proxy and says so:

```text
    TLS façade: Caddyfile reloaded
```

### Keycloak runs in production mode on PostgreSQL { #keycloak-runs-in-production-mode-on-postgresql }

The self-host Keycloak ran in development mode, with its realms in a file of its own. It now runs
in production mode, version 26.8.2, and keeps its realms in the stack's PostgreSQL, in a database
named `keycloak`.

- **`up.sh` moves your realm** at its first run: the `lumnik` realm, its users, their passwords
  and their attributes. It says so:

    ```text
    ==> moving Keycloak's realm from its H2 file to PostgreSQL (the volume lumnik_selfhost_kc is kept)
        realm lumnik moved, with its users and their passwords; every session ends: sign in again (lm login)
    ```

- **Every session ends.** Sign in again: `lm login`, and in the PWA.
- **The `master` realm starts anew.** Its admin is `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`
  from `.env`. A change you made by hand in `master` is not moved.
- **The old file stays**, in the volume `<folder>_selfhost_kc`. Nothing uses it any more. Once the
  new Keycloak has served for a while, remove it: `docker volume rm lumnik_selfhost_kc`.
- **If that file changes, `up.sh` moves it again**, over the realm in PostgreSQL: restoring an
  older snapshot into the volume does that. What you changed in Keycloak since is lost.
- **Keycloak hands out `http://localhost:8180` addresses only.** A port you opened to other
  devices no longer signs them in: put the [TLS proxy](../deploy.md#one-https-origin-the-tls-proxy) in front.
- **A backup now holds `keycloak.dump`.** `restore.sh` refuses a snapshot taken before this
  release: [Backup & Restore](backup-restore.md#troubleshooting).
- **If the move fails, Keycloak does not start**, and nothing else changed. The message says why
  and how to see more.

**The gesture:** re-run `./deploy/selfhost/up.sh`, then `lm login`.

**Helm, with the bundled Keycloak (`auth.mode=oidc`):** Keycloak runs in production mode too, and
keeps its realm in the release's PostgreSQL. Its first start imports the realm ConfigMap; from then
on, restarting Keycloak no longer re-reads it. Users and changes made in the console stay.

1. Refresh the two ConfigMaps from this release: the realm, and the PostgreSQL init scripts.

    ```bash
    kubectl create configmap lumnik-kc-realm --from-file=infra/keycloak/lumnik-realm.json \
      --dry-run=client -o yaml | kubectl apply -f -
    kubectl create configmap lumnik-pg-init --from-file=infra/postgres/init/ \
      --dry-run=client -o yaml | kubectl apply -f -
    ```

2. Add a password for Keycloak's database role to your secrets: `secrets.keycloakDbPassword`
   (`openssl rand -base64 32`), or the key `KEYCLOAK_DB_PASSWORD` of a sealed Secret.
3. `helm upgrade`. Keycloak waits for its database:
   `kubectl logs deploy/<release>-keycloak -c wait-for-database` says `waiting for the keycloak database`.
4. Create that database. With the bundled PostgreSQL:

    ```bash
    kubectl exec <release>-postgres-0 -- bash /docker-entrypoint-initdb.d/03-keycloak.sh
    ```

    With your own PostgreSQL, run `infra/postgres/init/03-keycloak.sh` as its superuser, with
    `KEYCLOAK_DB_PASSWORD` set to the same password.

Keycloak then starts and imports the realm. Every session ends: sign in again. A password set
since Keycloak's last restart, by `up.sh` or in the console, is not carried over: set it again.

- **Set `keycloak.hostname`** to Keycloak's public origin, on a deployment others reach. Without
  it, the links in Keycloak's emails follow the `Host` a request sends.
- **A later change to the bundled realm comes with its own gesture.** Refreshing the realm
  ConfigMap no longer changes a running realm.

### The public clients take no password { #the-public-clients-take-no-password }

`lm-cli` no longer takes a password, like `lumnik-spa` before it. Nobody who reaches the token
endpoint can try passwords there.

- **`lm login` is unchanged:** it uses the device flow.
- **An `lm` context first signed in by password** keeps working until its session ends. Then
  run `lm login`.
- **A script or a `curl` that minted a token by password** now gets
  `{"error":"unauthorized_client"}`. Use `lm token`: it prints the token of your `lm login`
  session, renewed when it is about to expire.

    ```bash
    curl -H "Authorization: Bearer $(lm token)" http://localhost:8080/api/platform/health
    ```

- **Postman:** a *Bearer Token* set from `lm token`, as in
  [Test the API](../recipes/test-the-api.md#the-same-thing-in-postman).
- **Self-host:** `./deploy/selfhost/up.sh` closes the password grant on both clients of an
  existing realm. It says so:

    ```text
      lm-cli: password grant now closed
    ```

- **Helm:** a running Keycloak keeps the realm it imported: refresh the realm ConfigMap and
  restart Keycloak, as in [An access token lives 5 minutes](#an-access-token-lives-5-minutes).
- **`lm token` comes with `lm` 1.10.0.** Update `lm` first.

**The gesture:** update `lm`; re-run `up.sh` (self-host) or refresh the realm (Helm); then replace
each password mint in your scripts with `lm token`.

### An access token lives 5 minutes { #an-access-token-lives-5-minutes }

The bundled realm's access tokens now live 5 minutes, not an hour. A role, a scope or an account
you disable in Keycloak stops within 5 minutes, even when the hub checks tokens locally.

- **The PWA and `lm` renew by themselves**, before a call. A phone that slept wakes into the app,
  unless it slept longer than the sign-in session's idle time (30 minutes): then it asks to sign in.
- **A token you pass yourself is never renewed:** `lm --token` or `LUMNIK_TOKEN` in a CI job, a
  token minted for `curl` or Postman. It lasts 5 minutes: mint another on `401`, or let `lm login`
  hold the session.
- **Self-host:** `./deploy/selfhost/up.sh` brings an existing realm's tokens to 5 minutes, and
  keeps a shorter value you chose. It says so:

    ```text
      access tokens: now 5 minutes (were 3600 s)
    ```

- **Helm:** a running Keycloak keeps the realm it imported at its last start. Refresh the realm
  ConfigMap, then restart Keycloak:

    ```bash
    kubectl create configmap lumnik-kc-realm --from-file=infra/keycloak/lumnik-realm.json \
      --dry-run=client -o yaml | kubectl apply -f -
    kubectl rollout restart deployment/<release>-keycloak
    ```

**The gesture:** self-host, re-run `up.sh`; Helm, the two commands above. Then mint the tokens your
scripts pin more often.

### The bundled Keycloak realm is stricter { #the-bundled-keycloak-realm-is-stricter }

A user could rewrite their own `tenant_id` from their account page once *Unmanaged attributes* was
**Enabled**. New installs get the stricter realm.

**An existing self-host install keeps its realm:** Keycloak imports the realm file once.

- **Run `./deploy/selfhost/up.sh`.** It declares `tenant_id` and `user_id` admin-only on your
  realm, and turns an **Enabled** *Unmanaged attributes* into **Admin can edit**. Your users keep
  their values. It says so:

    ```text
      tenant_id and user_id: now admin-only (the user profile declares them)
    ```

- **The rest is yours to set,** in the console (`http://localhost:8180`, realm **lumnik**):
    - **Realm settings → Events:** user events saved, expiring after 30 days; admin events saved,
      without representation.
    - **Authentication → Policies → Password policy:** Minimum length **12**, Not username, Not
      email.

What changes:

- **The PWA's client takes no password.** `up.sh` closes it, with `lm-cli`'s
  ([The public clients take no password](#the-public-clients-take-no-password)).

### The hub checks the address it connects to { #the-hub-judges-the-address-it-connects-to }

A source, a webhook or an export no longer reaches the cloud metadata address, nor `localhost`.
What is refused, and the settings: [Where the hub connects](../security/outbound-addresses.md).

- **A source or a webhook on `localhost` or `127.0.0.1` fails.** Nothing is sent:
  `the host resolves to a loopback address, which this hub refuses (lumnik.outbound.allow-loopback)`.
  - **The gesture, on a hub run next to its source:** set `LUMNIK_OUTBOUND_ALLOW_LOOPBACK=true` in
    the hub's environment, then recreate the hub. The self-host stack does not pass it from `.env`:
    [where to set it](../deploy.md#read-local-files-or-change-what-the-hub-fetches).
  - **In a container,** `localhost` is the container. Point the source at its host name instead.
- **On Helm, private addresses are refused too.** A source inside the cluster, such as
  `kubernetes.default.svc`, needs the chart value `outbound.allowPrivate=true`.
- **An Oracle connector named by a TNS alias fails:** `the hub cannot tell where this URL connects:
  it reads as a TNS alias`. So does an LDAP URL or a URL naming `TNS_ADMIN`.
  - **The gesture:** write the host into the URL (`jdbc:oracle:thin:@//erp-db:1521/ERP`), then
    `lm apply` the manifest.
- **A PostgreSQL URL with a `service=` parameter fails** the same way. Write the host into the URL.
- **A JDBC test or discovery answers `400` with the reason** when the address is refused. On a
  PostgreSQL server that never answers, it gives up within about 12 s.
- **A webhook test or replay no longer shows the receiver's body.** A failed delivery reads
  `HTTP 500` alone, those stored before too. Read the body in the receiver's own log.
- **A REST response over 64 MiB fails the run.** For a source that cannot page, raise
  `LUMNIK_OUTBOUND_MAX_RESPONSE_MB`.
- **Why:** a webhook test handed back whatever any address answered, the cloud metadata included.

### A connector reads only the secrets whose scopes it carries { #a-connector-reads-only-the-secrets-whose-scopes-it-carries }

A connector reads a secret only when it carries every scope of the secret. An export reads it only
when the secret's one scope is the export's scope. Every secret stored before this release has no
scope: it keeps serving the connectors without scopes, and no other.

- **A run of a connector with scopes fails**, naming the secret and the connector's scopes:
  `secret ERP_PASSWORD does not serve scope ventes: lm secret scope ERP_PASSWORD SCOPES replaces the scopes it serves`.
  Nothing was sent: the check runs before any request leaves the hub.
- **So does an export run, and a one-shot `lm source csv` ingest from S3 or SFTP.** Such an ingest
  always has a scope: `--scope`, or `system` when none is given.
- **The gesture:** give each secret the scopes of the connectors that name it. The value does not
  change, and you do not type it again.

    ```bash
    grep -rlE '_env: *ERP_PASSWORD' manifests/ | xargs grep -h 'scopes:'   # the scopes that name it
    lm secret scope ERP_PASSWORD ventes
    lm secret list                                                       # the SCOPES column
    ```

- **One secret named by connectors of different scopes** (`ventes` and `achats`): give it to one
  scope, and store a copy for the other. Point that scope's connectors at the copy.

    ```bash
    lm secret set ERP_PASSWORD_ACHATS        # the same value
    lm secret scope ERP_PASSWORD_ACHATS achats
    ```

    Scoping it `ventes,achats` serves neither: it then serves only connectors carrying both.

- **Why:** a scope-bound integrator may write connectors in its own scopes. It could name any secret
  of the tenant in one, point it at its own server, and receive the value.

### A declared index is per tenant { #a-declared-index-is-per-tenant }

An index declared under `indexes:` (csv-file, jdbc-generic) now starts with `tenant_id`. A
`connector.t_*` table is shared by every tenant ingesting under that name, so a unique index on
`code` alone let one tenant's key refuse another tenant's row.

- **The upgrade rebuilds the existing declared indexes**, under their own names, as
  `(tenant_id, <their columns>)`. Until it finishes, the tables that carry one take no writes, and
  the disk holds both copies of each index: on large tables this lengthens the upgrade. Reads go on
  until the last moment.
- **With several hub replicas, stop the runs while the upgrade applies.** An older replica writing
  to a table at that moment can make it fail, and a table it creates then gets an index of the old
  shape.
- **`unique` now means unique within a tenant.** No existing row can break the new index.
- **A declared index the upgrade cannot rebuild is left as it was, and named.** That is one a
  constraint or a foreign key holds, or one your database administrator set up for replication or
  on another disk. The upgrade log names each one in a warning that ends `rebuild it by hand with
  tenant_id first`. Do so, with `tenant_id` as its first column. An index you built yourself in another shape is left as it is, without a warning.

### A REST target is a table under ext or connector { #a-rest-target-is-a-table-under-ext-or-connector }

An endpoint's `target.table` is `ext.<name>`, or `connector.<name>` for an Entity to fuse it.
The hub writes that table as its own database owner, so any other schema is refused.

- **`lm apply` refuses another schema, or a bare name**, at `spec.endpoints[i].target.table`:
  `must be ext.<name> or connector.<name>`. A connector stored before this release fails its runs
  with `target.table must be ext.<name> or connector.<name> (…): <its table>`. A save that carries
  its config is refused until the target is renamed.
- **A run refuses an existing table the hub did not make**, before it changes anything in it:
  `connector.connector is not a table this hub's writer made: name a new one under ext or connector as target.table`.
- **The gesture:** name the target under `ext`, then `lm apply -f` again. The table is created at
  the next run; the rows of the old one are not moved. A table the hub made under another schema
  (`public.<name>` for a bare name) is neither purged nor dropped with its connector: drop it by hand.

### The hub writes only the REST tables it made { #the-hub-writes-only-the-rest-tables-it-made }

A REST endpoint and a one-shot csv write their rows into a table under `ext` or `connector`. The
hub now keeps the list of the tables it made. A run writes only into a table on that list. Purging
an endpoint and deleting a connector touch only those tables too.

- **The upgrade lists the tables the hub already made.** Nothing to do for them.
- **A run refuses any other existing table**, before it changes anything in it:
  `ext.orders is not a table this hub's writer made: name a new one under ext or connector as target.table`.
- **The hub repairs its own tables.** After each restart, the first run on a table puts back what
  keeps each tenant's rows apart and what lets Ask read them, if something was removed by hand.
- **A run refuses a table where someone added an access rule by hand.** Such a rule can show a
  tenant the rows of another:
  `ext.orders carries the policy see_all, which the hub did not make and which widens what each tenant sees: drop it to write here again`.
- **A table is created complete, or not at all.** If its creation fails, the run fails and leaves
  no table. The next run creates it again.
- **The first run on a table after a restart may start late:** it waits for the runs already
  writing into that table. So does a run that adds a promoted column. The other runs do not wait.
- **With several hub replicas, stop the runs and the connector deletes while the upgrade applies.**
  An older replica does not keep the list: a table it creates then is refused afterwards.

**The gesture:**
- **For a refused access rule:** remove it as the database owner, then run again:
  `DROP POLICY see_all ON ext.orders;`
- **For a table the hub made that is not on the list** (restored by hand, or created by an older
  replica during the upgrade): add it as the database owner, then run again. No `lm` command does
  this: it is one SQL line. Use the name `\dt ext.*` shows: Postgres cuts names at 63 characters.
  `INSERT INTO connector.hybrid_table (table_schema, table_name) VALUES ('ext', 'orders');`

### A hook override carries no code { #a-hook-override-carries-no-code }

A tenant admin could store a Kotlin script as a hook override, and the hub ran it in its own process. It no longer
does: the hub runs the operator's scripts only, and an override only switches a hook on or off for its tenant.

- **`POST /api/platform/hooks/overrides` is refused** (`400`, `a hook override carries no code`). Switch a hook with
  `POST /api/platform/hooks/{hookId}/toggle`.
- **The upgrade clears the scripts already stored.** Export them before upgrading. A hook's code is now its
  module's: the script declared in the module's manifest (`handler: { type: kotlin, script: … }`), built into the
  hub. No command or setting brings an exported one back: it runs again only in a hub image you build yourself,
  with the script in a module's manifest. The hooks directory serves connector mapping scripts only, not lifecycle
  hooks.

    ```bash
    docker compose -f docker-compose.selfhost.yml exec -T postgres sh -c 'psql -U "${POSTGRES_USER:-lumnik}" -d "${POSTGRES_DB:-lumnik}" -c \
      "SELECT tenant_id, hook_id, script_content FROM core.hook_tenant_override WHERE script_content IS NOT NULL"'
    ```

- **The hook endpoints refuse a caller bound to a `scope:*` role**, as the webhook and notification endpoints do, and list
  the caller's tenant's hooks only.

### A secret name resolves in `lm secret` only { #a-secret-name-resolves-in-lm-secret-only }

A manifest's `*_env` key — `password_env`, `token_env`, `key_env`, `brokers_env`… — names an
`lm secret` entry of the connector's tenant. So does SOAP's `{{env:NAME}}`. The hub's environment is
no longer read: it holds the hub's own passwords, and any tenant could name one.

- **A name only the hub's environment held fails the run**, naming it:
  `password_env 'ERP_DB_PASSWORD' is not in lm secret`.
- **The gesture:** put each value in the tenant that uses it, under the same name. The manifest
  does not change.

    ```bash
    grep -rhoE '[a-z_]+_env: *[A-Za-z0-9_-]+' manifests/ | sort -u   # the names your manifests use
    lm secret set ERP_DB_PASSWORD                                     # prompts; what you type is not shown
    ```

    On the self-host stack, a value kept in `.env`:
    `grep '^ERP_DB_PASSWORD=' .env | cut -d= -f2- | lm secret set ERP_DB_PASSWORD -`.

- **S3 without `access_key_env` / `secret_key_env`** reads the `lm secret` entries
  `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. The hub's own AWS credentials are never used.
- **Event Hubs:** re-apply the manifest (`lm apply`). The preset now writes its fixed username,
  `$ConnectionString`, as itself: no secret to set for it.
- **A JVM property (`-DNAME=…`) is not read either.**

### The local transport reads one folder { #the-local-transport-reads-one-folder }

- **A `csv-file` connector with `transport.kind: local`** reads its tenant's folder of
  `LUMNIK_CSV_LOCAL_ROOT` only: `<root>/<tenant id>`. Its `path` is relative to that folder; an
  absolute one is refused at `lm apply`, and fails the run of a connector stored before. Unset — the
  default — every run fails:
  `the local transport is off on this hub` ([CSV § Local](../connectors/csv.md#local-the-hubs-disk-not-yours)).
- **The gesture:** set `LUMNIK_CSV_LOCAL_ROOT`, then recreate the hub as in step 3: `deploy/selfhost/up.sh`
  (add `--build` for a stack run from source), or `helm upgrade` with it in `hub.extraEnv`. A plain
  restart keeps the old environment. Move each tenant's files into
  `<root>/<tenant id>/`. Rewrite each `path` relative to it (`/imports/*.csv` → `imports/*.csv`),
  `lm apply` the manifest, then start its endpoint from the first file again: the stored position
  names the old path. Until its path is rewritten, such a connector is also refused when saved through
  `lm connector apply` or the TUI.

    ```bash
    lm endpoint reset-cursor orders-drop/default
    ```

- **`lm csv ingest ./file.csv` is unchanged.** Its endpoint no longer reads a path of the hub's disk: a
  request naming one is a `400` (`a local ingest is the uploaded file`).

### A hub started by hand refuses the published defaults { #a-hub-started-by-hand-refuses-the-published-defaults }

This is for a hub you start yourself: `docker run` of the image, or the jar. The self-host stack and
the Helm chart set every value already: nothing changes for them.

The hub no longer starts on a default this repository publishes. It names each setting and its
variable, never the value:

```text
lumnik refuses to start on a published default:
```

followed by one line per setting:

- the crypto key: set `LUMNIK_CRYPTO_SECRET_KEY` (`openssl rand -base64 32`);
- the three database passwords: `LUMNIK_APP_DB_PASSWORD`, `LUMNIK_ADMIN_DB_PASSWORD`,
  `LUMNIK_READONLY_DB_PASSWORD`, in the hub and in PostgreSQL;
- `lumnik.security.dev-bypass`, which checks no token: turn it off;
- `LUMNIK_TENANCY=single`: set `auto` or `multi`.

**The gesture:** set your own key and your own passwords. A hub in dev mode keeps its defaults.

### An Entity reads connector tables only, within their scopes { #an-entity-reads-connector-tables-only-within-their-scopes }

An Entity's `sources[].table` and `joins[].table` are checked when you apply it.

- **A table no connector writes is refused:**
  `source table <name> is not a table a connector writes: an Entity fuses connector.t_<name> tables, the REST targets the hub made under connector, and other Entities' views`.
- **An Entity over another Entity's view** declares only scopes that view has. Otherwise its readers
  would read the other Entity without holding its scopes.
- **An Entity over a view whose Entity is not applied yet** answers `409`: apply that Entity first.
- **A caller with a `scope:*` role** applies an Entity only when every connector writing its tables
  carries every scope the Entity declares.
- **A connector cannot drop a scope an Entity reading its tables needs**: not by its tags, nor by
  re-applying its manifest. Its first write into such a table is refused the same way.
- **Entities applied before this release** keep serving until they are re-applied.

**The gesture:** re-apply your Entities after the upgrade, to see a refusal now rather than at the
next rebuild. For a scope refusal, align the Entity's `scopes:` with the connectors that write its
tables.

### A caller with a scope role is refused the tenant-wide work { #a-caller-with-a-scope-role-is-refused-the-tenant-wide-work }

Some work acts on the whole tenant: secrets, users and roles, organisations, tenants, hooks, event
dead letters, `lm rag reindex`, SCIM tokens. A caller holding a `scope:*` role is now refused it,
whatever tenant the URL names:

```text
403 {"error":"scope not granted"}
```

A caller with no `scope:*` role is unchanged. `lm hook list` now needs `lm_admin`, like the other
hook commands, and lists your tenant's hooks only.

**The gesture:** do this work as an admin with no `scope:*` role.

### Ask stays closed until the read-only role is hardened { #ask-stays-closed-until-the-read-only-role-is-hardened }

Ask runs the model's SQL as a read-only database role. That role must not be able to switch tenant.
A superuser file takes those rights from it, and Ask stays closed until it has run. Everything else
works meanwhile.

- **Nothing to do** on self-host (`deploy/selfhost/up.sh`) and on Helm with `LUMNIK_PG_MODE=external`:
  each start runs the file.
- **Run it once** on Helm with the bundled database, on the dev `docker-compose.yml`, or on a
  PostgreSQL you run yourself:

    ```bash
    psql -U <superuser> -d lumnik -f infra/postgres/init/02-extensions.sql
    ```

- **Until then, a question answers `503`:**
  `analytic ask is unavailable: the readonly database role is not hardened`. Ask opens by itself
  once the file has run, without a restart.
- **From 1.14.0 the file also takes sleeps, notify and large objects** from the role. Run it again
  where the hub does not: Ask answers `503` until then, and the hub's log names what the role still
  holds: `Ask is closed: the readonly role still holds …`.
- **A query of Ask stops after 5 seconds**, and a result over 16 MB is refused:
  `The result of this query is too large to read. Ask for fewer rows or shorter columns.`
- **A backup role that is not a superuser** needs `set_config` back for `pg_dump`:
  `GRANT EXECUTE ON FUNCTION pg_catalog.set_config(text,text,boolean) TO <backup role>;`
- **A table you create by hand under `connector`** is no longer readable by Ask on its own. Grant it:
  `GRANT SELECT ON connector.<table> TO lumnik_readonly;`
- **Ask refuses a name that starts with `pg_`,** a string written `E'…'` or `U&'…'`, and a name written `U&"…"`. The model
  rarely writes them; when it does, the question answers `I couldn't translate that into a safe
  query.` Ask again in other words. A table or column named `pg_…` cannot be read by Ask: rename it.

More: [What the read-only role cannot call](../security/rls.md#what-the-read-only-role-cannot-call).

### A rate limit is per user, under a tenant ceiling { #a-rate-limit-is-per-user-under-a-tenant-ceiling }

- **Each user has their own budget.** One busy user no longer gets the whole tenant refused.
- **Each tenant has a ceiling:** the limit × `LUMNIK_RATELIMIT_TENANT_FACTOR` (5; `0` switches it off).
- **Probing sources has its own limit**, 60 per minute: the JDBC test, discover and add, the REST
  probe and the OpenAPI import (`LUMNIK_RATELIMIT_SOURCE_PROBE_RPM`).
- **SCIM has its own limit**, 600 per minute (`LUMNIK_RATELIMIT_SCIM_RPM`). Raise it for a first sync
  of thousands of users.
- A refused call answers `429` with `rate limit exceeded` and a `Retry-After` header.

**The gesture:** a script that probes sources in a loop may now meet `429`. Slow it down, or raise
the limit.

### The self-host stack: HSTS on request, three more settings read { #the-self-host-stack-hsts-on-request-three-more-settings-read }

- **HSTS comes from the TLS façade only, and only when you ask:** `LUMNIK_TLS_HSTS=true`. Use it for
  a public name with a real certificate; leave it off on a LAN name or `localhost`.
- **`OIDC_SUBJECT_CLAIM`, `LUMNIK_AUTH_VERIFY_WITH_IDP` and
  `LUMNIK_CONNECTOR_MAX_CONCURRENT_SCHEDULED_RUNS` are now read from `.env`.** A value you set there
  was ignored: it now takes effect.
- **`./deploy/selfhost/backup.sh` writes files readable by their owner only.** If another account
  ships `backups/`, let that account run the backup itself.

**The gesture:** read your `.env` for those three settings, then re-run `up.sh`.

### The Helm chart's pods run with fewer rights { #the-helm-charts-pods-run-with-fewer-rights }

- **Every pod mounts no service-account token and runs under the runtime's seccomp profile.** Every
  container drops its Linux capabilities and gains no right. Every pod but PostgreSQL runs as a
  non-root user. PostgreSQL's start still prepares its data as root, with five capabilities, then
  drops to its own user: your data is untouched.
- **The hub and PostgreSQL cannot change their own files.** The hub writes in `/tmp` only, an
  `emptyDir` of `hub.tmpSizeLimit` (8 GiB). Raise it if a connector reads a larger file.
- **The bundled PostgreSQL answers the hub only**, and the search cluster answers the hub only.
  Neither opens a connection out. A NetworkPolicy refuses any other traffic into the release's pods;
  the hub and Keycloak stay reachable on their ports.
- **Keycloak, PostgreSQL and the search cluster have a memory limit:** `keycloak.resources`,
  `postgresql.resources`, `search.cluster.resources`.

**The gesture:** `helm upgrade` as usual. Then list what of yours reaches the release's pods from
another pod: it is now refused.

- A backup job or pgAdmin on the bundled PostgreSQL.
- OpenSearch Dashboards or a snapshot job on the search cluster.
- A Prometheus scraping Keycloak.
- A Prometheus scraping the hub's metrics: name it in `hub.managementFrom` instead
  ([Scraping with Prometheus](observability.md#scraping-with-prometheus)).

Add a NetworkPolicy that lets that pod in, or run the tool with `kubectl exec` in the target pod.

### The self-host containers run with fewer rights { #the-self-host-containers-run-with-fewer-rights }

- **The hub, Postgres, Keycloak, the TLS façade and Mailpit gain no right they did not start
  with**, and each one drops its Linux capabilities. Postgres keeps the five its start needs. The
  TLS façade keeps the right to listen on 80 and 443. The search cluster (`--search-cluster`) is
  not hardened yet.
- **The hub, Postgres, the TLS façade and Mailpit cannot change their own files.** The hub writes
  in `/tmp` only. `/tmp` is now a volume, `selfhost_hub_tmp`: a downloaded CSV stays on disk.
- **A file copied into the hub outside `/tmp` is refused:**
  `Error response from daemon: container rootfs is marked read-only`.

**The gesture:** re-run `up.sh`. A `LUMNIK_CSV_LOCAL_ROOT` on a volume you mount keeps working. One
you filled with `docker compose cp` outside `/tmp` does not: put it under `/tmp`, as in the
[CSV guide](../connectors/csv.md), copy the files again, then
`lm endpoint reset-cursor <connector>/<endpoint>`.

### A REST header reference must resolve { #a-rest-header-reference-must-resolve }

A REST connector header declared with `value_env` now reads like the auth fields: the `lm secret`
entry first, then the hub's environment ([REST § request headers](../connectors/rest.md#request-headers)).

- **A name that resolves nowhere fails the run**, `lm source rest test` and `lm source rest probe`:
  `Header X-Client-Id unavailable (env=CLIENT_ID)`. Before, the header was dropped and the request
  went out without it. Store the value (`lm secret set CLIENT_ID`), or remove the header from the
  manifest if the source does not need it.
- **An `lm secret` entry now outranks an environment variable of the same name** for a header. If
  both exist with different values, the secret's value is sent: remove the one you do not want.

### Subscriptions are tenant-wide

Webhooks and email rules are refused to an admin bound to any `scope:*` role — see
[Outputs](../outputs.md). Ones a bound admin created earlier keep firing. A tenant-wide admin lists
them and removes what nobody owns:

```
$ lm notify list
ID    EVENTS            RECIPIENT        TEMPLATE            ENABLED
1000  decision.*        adv@example.com  decision-alert      true
1001  data.row.changed  ops@example.com  event-notification  true
$ lm integration list
ID    NAME            URL                        EVENTS                      ENABLED
1000  ops-automation  https://hooks.example.com  decision.*,connector.run.*  true
$ lm notify rm 1001
removed: 1001
$ lm notify list
ID    EVENTS      RECIPIENT        TEMPLATE        ENABLED
1000  decision.*  adv@example.com  decision-alert  true
```

A webhook goes the same way: `lm integration rm <ID>`.

### One local csv file is the table's state { #one-local-csv-file-is-now-the-tables-state }

A `csv-file` connector whose local `path` names one file (no `*` or `?`) removes the rows the file
no longer holds — see [CSV § full refresh](../connectors/csv.md#one-file-is-the-table-full-refresh).
Before the upgrade, the table kept every older version of a changed row. The first run that reads
the file after upgrading counts those older versions as removed. A file is read again only once
it changes. Later runs count only the rows the file lost. On that first run:

- up to 30 % of the table: they are removed;
- beyond 30 %: the run fails, removes nothing and names the counts;
- to keep them, point the connector at a folder (`*.csv`); to let them go, set
  `refresh.max_removed: off` for that run, then remove the line.

### A jdbc table holds one row per key { #a-jdbc-table-now-holds-one-row-per-key }

A `jdbc-generic` table holds one row per `pk_columns` value
([JDBC § one row per key](../connectors/jdbc.md#one-row-per-key-a-changed-row-replaces-its-version)).
The first run after upgrading keeps the newest row per key and removes the older versions, even
when it reads nothing new:

```
$ lm connector run demo-invoices
run scheduled: 13629016-e970-4a1a-92b7-aed86627f3f8
$ lm run get 13629016
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
13629016  demo-invoices  invoices  Completed  0   0    0        0             2026-10-18 13:49  2026-10-18 13:49
• 2 row(s) removed — the source no longer holds them
```

If one earlier run read the same key twice with two different values, the run fails naming
`pk_columns` and removes nothing. Declare the source table's whole primary key, apply, run again.

## Running from source

```bash
git pull
make -C lm install                 # installs lm into ~/.local/bin (keep it on your PATH)
./deploy/selfhost/up.sh --build    # rebuilds and restarts the hub
```

Your data is kept; migrations run at the hub's next boot. On a TLS-proxy install, run its
command again afterwards.

## Roll back

There is no down-migration. Rolling back is restoring the backup taken in step 1; writes made
since are lost.

1. Set `LUMNIK_VERSION` in `.env` back to the previous release.
2. `deploy/selfhost/up.sh`: the hub comes back on the previous image.
3. `deploy/selfhost/restore.sh backups/<UTC-stamp>`: the database as it was before the upgrade.

## If the boot fails mid-migration

The migration stops at the failing version; later migrations do not touch your data, and the hub
does not become healthy.

1. Save the log: `docker compose -f docker-compose.selfhost.yml logs hub > hub-upgrade.log`.
2. Need service back now? [Roll back](#roll-back).
3. Report the failing version: the last `Migrating schema … to version …` line in the log.

## Changing the public address

Do not change the hub's public address during an upgrade: every user would log in as a new one.
[Keep the same public address](../security/byo-idp.md#keep-the-same-public-address).
