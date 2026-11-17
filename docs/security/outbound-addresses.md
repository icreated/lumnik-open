# Where the hub connects

A REST source, an OpenAPI spec, a webhook, a JDBC source, an S3 endpoint, an SFTP server and a
Kafka broker each name an address. The hub checks that address before it sends anything. It
checks where the name leads, not the name itself.

## What the hub refuses

| Address | Examples | Refused |
|---|---|---|
| The cloud metadata | `169.254.169.254`, and the AWS, Alibaba, Azure and Oracle Cloud equivalents | always |
| Link-local, unspecified, multicast | `fe80::1`, `0.0.0.0`, `224.0.0.1` | always |
| The hub's own machine | `localhost`, `127.0.0.1`, `::1` | unless `LUMNIK_OUTBOUND_ALLOW_LOOPBACK=true` |
| Private networks | `10.x`, `172.16–31.x`, `192.168.x`, `100.64–127.x`, `fc00::/7`, and the other non-public ranges | when `LUMNIK_OUTBOUND_ALLOW_PRIVATE=false` |

Another way of writing the same address is the same address: `::ffff:127.0.0.1` is `localhost` too.

## The settings

Each is read at startup. A change takes a restart.

- **`LUMNIK_OUTBOUND_ALLOW_LOOPBACK`**, default `false`.
  - Set it to `true` for a hub run as a plain process on the source's own machine.
  - In a container, `localhost` is the hub itself. Point the source at its host name instead.
- **`LUMNIK_OUTBOUND_ALLOW_PRIVATE`**, default `true`, because an on-premise ERP sits on the LAN.
  - The Helm chart sets it to `false`: it serves several tenants, and the cluster's own services
    sit on private addresses.
  - For a source inside the cluster, set the chart value `outbound.allowPrivate=true`
    (`LUMNIK_OUTBOUND_ALLOW_PRIVATE=true make k8s-up` on kind).
  - On the self-host stack, the hub's database and Keycloak are on private addresses too. Set
    `false` when tenants must not reach them.
- **`LUMNIK_OUTBOUND_MAX_RESPONSE_MB`**, default `64`: the largest REST response the hub reads.

## What a refusal looks like

The refusal names the kind of address and the setting, never the address:

```
$ lm source rest from-openapi http://169.254.169.254/latest/meta-data/ --name meta
Error: API error 400: 'url': the host resolves to a link-local address, which the hub never connects to
```

- **Nothing is sent.** A refusal is not retried.
- **A run fails with the same sentence.** So do `lm source rest test`, `lm source rest probe` and
  an export.
- **A webhook is registered anyway.** Its address is checked at each delivery, and the delivery
  fails with the sentence:

    ```
    $ lm integration test 1000
    Error: delivery failed: the host resolves to a loopback address, which this hub refuses (lumnik.outbound.allow-loopback)
    ```

## A JDBC URL

Write the host in the URL. The hub reads it there, as the driver does.

- **PostgreSQL:** `jdbc:postgresql://host:5432/db`, or a failover list `//a:5432,b:5432/db`.
  - **Refused:** a URL with no host (`jdbc:postgresql:///db`), and a `service=` parameter, which
    the hub's own files would resolve.
- **Oracle:** `@//host:1521/SERVICE`, `@host:1521/SERVICE`, `@host:1521:SID`, a host list,
  `tcps://`, or a `(DESCRIPTION=…)` descriptor.
  - **Refused:** a TNS alias, an LDAP URL, `TNS_ADMIN`, and a proxy parameter.

```
$ echo -n "$ERP_PASSWORD" | lm source jdbc test --url jdbc:oracle:thin:@ERPPROD --user ro --password-stdin
Error: API error 400: the hub cannot tell where this URL connects: it reads as a TNS alias
$ echo -n "$ERP_PASSWORD" | lm source jdbc test --url jdbc:postgresql://169.254.169.254:5432/erp --user ro --password-stdin
Error: API error 400: the host resolves to a link-local address, which the hub never connects to
```

## What else the hub bounds

- **A REST response over 64 MiB fails the run:** `the response is larger than 64 MiB, the most the
  hub reads; page the endpoint, or raise lumnik.outbound.max-response-mb`. Nothing past it is read.
- **A webhook test or replay shows the receiver's status, never its body.** A failed delivery
  reads `HTTP 500`. Read the body in the receiver's own log.
- **An OpenAPI spec is fetched over `http` or `https` only.** A `$ref` to another document is not
  followed.
- **No redirect is followed.**
- **SFTP offers only the credential the transport declares**, never the hub's own SSH keys.
- **A JDBC test, discovery or guided add gives up** on a server that never answers: within about
  12 s on PostgreSQL. A run's queries are not cut short.

## Limits

- **A name that changes its answer.** The hub looks the name up, then the client connects. Both
  use the same 30-second cache, so they agree. For REST, webhooks, JDBC and SFTP, the client
  connects right after each check: a name can slip through only if its answer changes at that
  very moment. A hub that turns the cache off logs a warning at startup.
- **Kafka and S3 are checked once, when the run starts.** Their client keeps looking the name up
  for the whole run. A name that later leads elsewhere is not checked again.
- **A name that does not resolve:**
  - for Kafka and S3, the run fails with `host not resolved` and is retried;
  - for the others, it fails as the client says (host not found), and is retried like any
    unreachable source. In a failover list, the client moves on to the next host.
- **Kafka:** the hub checks the broker addresses you write. The cluster then names its other
  brokers itself, and those are not checked.
- **Oracle:** a listener that sends the connection on to another host is not checked.
- **A JDBC URL that sets its own timeouts keeps them.**
