# Self-host lumnik

Stand up the whole lumnik stack — hub + PostgreSQL (pgvector) + Keycloak (real OIDC) — on a single
host with Docker. It runs anywhere Docker runs: a laptop, or a VM on any cloud (GCP, AWS, Azure, Scaleway).

> "Deploy anywhere" needs a portable image on a VM, not per-cloud infrastructure-as-code. That's this.
> Managed-service / Terraform paths come later, driven by a real deployment's needs.

## Prerequisites

- Docker + Docker Compose v2 (`docker compose version`).
- The published hub image `ghcr.io/icreated/lumnik-hub` is **private pre-launch** — `docker login ghcr.io`
  authenticates you but grants **no access** unless your GitHub account was added to the package.
  The path that works for everyone: `--build` (compiles from source inside Docker — no image access
  and no local Maven/JDK needed, only Docker; the first build takes a few minutes).
- **For the natural-language layer (RAG chat, semantic search, analytic "ask"):** an
  [Ollama](https://ollama.com) running on the host, with the three models pulled. Without it,
  *structured* ingestion, canonical fusion, and the REST API still work — but the embedding model
  is unreachable, so RAG indexing **skips its chunks** (semantic search/chat return nothing) and
  "ask" has no model to call. The hub reaches the host Ollama via `host.docker.internal`
  (wired in the compose file); override with `OLLAMA_BASE_URL` in `.env` for a remote Ollama.

  ```bash
  ollama serve                      # if not already running
  ollama pull nomic-embed-text      # embeddings (RAG indexing)
  ollama pull llama3.2              # conversational RAG chat
  ollama pull qwen2.5-coder:7b      # text-to-SQL "ask" (4.7GB — the analytic model)
  # optional: ollama pull gemma4    # the offline faithfulness @Judge eval
  ```

  > **Linux host:** `host.docker.internal` resolves to the **docker bridge IP**, never to
  > the host's loopback — and the Ollama systemd service binds `127.0.0.1` by default, so
  > the hub cannot reach it as installed (Docker Desktop on macOS/Windows routes to the
  > host loopback and masks this). Bind Ollama on the bridge, container-only:
  >
  > ```bash
  > mkdir -p /etc/systemd/system/ollama.service.d
  > printf '[Service]\nEnvironment="OLLAMA_HOST=172.17.0.1:11434"\n' \
  >   > /etc/systemd/system/ollama.service.d/override.conf
  > systemctl daemon-reload && systemctl restart ollama
  > ```
  >
  > `172.17.0.1` is Docker's default bridge gateway — if you customized the bridge
  > (daemon.json `bip`, rootless), use what
  > `docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}'` prints.
  >
  > Nothing is exposed publicly (172.17.0.1 is only reachable from containers) — but if a
  > host firewall is active, allow the container range in:
  > `ufw allow from 172.16.0.0/12 to any port 11434 proto tcp`. Source-range matching is
  > deliberate: compose networks get their own `br-*` bridges (not `docker0`), so an
  > interface-scoped rule would miss them. The /12 covers Docker's default address
  > pools — narrow it if your host also carries VPN/corporate subnets in that range.
  > The same rule applies to
  > **any** host service the hub must reach through `host.docker.internal`. On a
  > CPU-only box, `Environment="OLLAMA_KEEP_ALIVE=-1"` in the same override keeps the
  > model loaded — first-question cold loads take tens of seconds otherwise.

## Quick start

```bash
./deploy/selfhost/up.sh --build   # build from source — works for everyone (the image is private pre-launch)
# ...or, if your GitHub account has access to the published image:
./deploy/selfhost/up.sh           # pull the image, generate secrets, start, wait for healthy
```

On the first run, `up.sh` creates `.env` with strong random secrets (`openssl rand`). When the hub is
healthy it prints the URLs:

- Hub — `http://localhost:8080` (health: `/q/health` — unauthenticated; the `/api/platform/*` API needs a token)
- Keycloak — `http://localhost:8180/realms/lumnik`

Then point `lm` at the hub (server `http://localhost:8080`, OIDC `http://localhost:8180/realms/lumnik`).

```bash
./deploy/selfhost/logs.sh hub     # tail logs (omit the service name for all)
./deploy/selfhost/down.sh         # stop the stack
./deploy/selfhost/down.sh --volumes   # stop AND delete all data (full reset)
```

## Secrets

`up.sh` writes `.env` (gitignored, mode `600` — owner-only) with six secrets: the Postgres and Keycloak
admin passwords, the two hub encryption keys (`LUMNIK_SECRET_MASTER_KEY`, `LUMNIK_CRYPTO_SECRET_KEY`),
and the per-install passwords for the demo realm users (`HUBADMIN_PASSWORD`, `INTEGRATOR_PASSWORD` —
no known password ever survives an install).
**Back `.env` up** —
losing `LUMNIK_SECRET_MASTER_KEY` makes secrets stored by the hub unrecoverable. To pin a specific image
version instead of the latest `main` build, set `LUMNIK_VERSION` in `.env` (e.g. `LUMNIK_VERSION=1.0.0`
once a `hub-v*` release is tagged).

## Ports

By default the hub (`8080`) and Keycloak (`8180`) bind to `127.0.0.1` — reachable from the host only.
For a remote host, access them over an SSH tunnel, or see "Going public" below.

## Going public — the TLS façade (shipped)

To expose lumnik beyond localhost with one HTTPS origin, use the **TLS façade overlay**
(`deploy/tls/`): a single Caddy origin fronting the hub and path-proxying Keycloak under
`/realms` — one issuer for every device. A LAN install gets an internal CA certificate;
setting a public `LUMNIK_TLS_HOST` gets Let's Encrypt automatically.

Runbook: [deploy/tls/README.md](../tls/README.md) — also summarized on the
[deploy page](../../docs/deploy.md).

## Postgres bootstrap user

Fresh installs bootstrap Postgres as superuser **`lumnik`**. Installs created before the
rename run as the legacy `adempiere` — the name is frozen into the data volume at first
init, so `up.sh` pins `POSTGRES_USER=adempiere` into an older `.env` automatically and
everything (healthcheck, backup, restore) keeps working. No action needed either way.

## Backup & restore

Nightly-able backup (hot pg_dump + Keycloak store) and restore, including onto a
fresh machine: `backup.sh` / `restore.sh` here — full runbook at
[docs/operations/backup-restore.md](../../docs/operations/backup-restore.md).
Back up `.env` separately: it holds the master key.

## Residuals

The hub's internal DB-role passwords (`lumnik_app` / `admin` / `readonly`) use built-in defaults; Postgres
is not published outside the compose network, so it is internal-only. Making those configurable is a
tracked follow-up.

## Email delivery (optional)

Rules (`lm notify add`) route matching events to email. Delivery is **mocked until both
of these knobs are set** in `.env` (set only one and the hub WARNs at boot):

```bash
LUMNIK_SMTP_HOST=smtp.example.com
LUMNIK_SMTP_MOCK=false
# optional: LUMNIK_SMTP_PORT=587 LUMNIK_SMTP_USERNAME=… LUMNIK_SMTP_PASSWORD=… LUMNIK_SMTP_FROM=…
```

The delivery drill, end to end, with the bundled dev mailer:

```bash
docker compose --profile mailpit -f docker-compose.selfhost.yml up -d
# .env: LUMNIK_SMTP_HOST=mailpit  LUMNIK_SMTP_PORT=1025  LUMNIK_SMTP_MOCK=false
docker compose -f docker-compose.selfhost.yml up -d hub
lm notify add --events 'decision.*' --to you@example.com
# trigger a decision (or lm integration test on a rule-matched type), then open:
# browse to http://127.0.0.1:8025 — the sent mail, visible
```
