# Prerequisites — prepare the ground

> What must be ready **before the integrator arrives**, in one checklist.

This list serves the [guided path](../parcours.md) and any first self-host install.
Items marked *(real ingestion only)* are not needed for the demo path itself.

!!! note "On Windows, start elsewhere"
    Follow [Deploy on Windows](../deploy-windows.md) **first** — it installs WSL2 (admin
    rights + a restart, exactly what to get done before the integrator arrives) and says
    which window each command below goes in. Then come back to this checklist.

## The machine

| Item | Requirement | Notes |
|---|---|---|
| Docker + Docker Compose v2 | required | a laptop or any cloud VM works |
| Tools | `curl`, `python3`, `openssl`, `awk`, `jq` | `up.sh` refuses to start without the first four; the self-check below uses `jq`. Debian/Ubuntu: `sudo apt install -y curl python3 openssl gawk jq` |
| Sizing | **4 vCPU / 16 GB / SSD**, measured for 50 concurrent users ([sizing](../operations/sizing.md)) | the model needs its own budget — see below |
| Free disk | **~20 GB** for a comfortable first install (images + models + data) | the models alone are most of it |
| Outbound network | required **during install**: github.com (clone), the container registries, ollama.com (models) | at runtime the AI layer is local by default ([Models](../rag/models.md)) |
| Air-gapped install | **not supported** | no image pre-load or mirror tooling ships |
| Go 1.26+ | **only to build `lm` from source** — pre-built archives are on every [release](https://github.com/icreated/lumnik-open/releases/latest) | |

## The AI layer (Ollama)

**lumnik Pro only** — the open edition does not use Ollama; skip this section.
Chat, semantic search and ask need an Ollama the hub can reach. **Install
[Ollama](https://ollama.com) on the host** (not in a container), then pull the three models:

```bash
ollama pull nomic-embed-text      # embeddings
ollama pull llama3.2              # conversational chat
ollama pull qwen2.5-coder:7b     # text-to-SQL ask (4.7 GB — the analytic model)
```

Budget **~8 GB of downloads**. No GPU is needed for the guided path.

### On a Linux host: let the hub reach Ollama

As installed, Ollama listens only on the host itself. The hub runs in a container and cannot
reach it. Make Ollama listen on Docker's bridge:

```bash
sudo mkdir -p /etc/systemd/system/ollama.service.d
printf '[Service]\nEnvironment="OLLAMA_HOST=172.17.0.1:11434"\nEnvironment="OLLAMA_KEEP_ALIVE=-1"\n' \
  | sudo tee /etc/systemd/system/ollama.service.d/override.conf
sudo systemctl daemon-reload && sudo systemctl restart ollama
```

- Only containers can reach `172.17.0.1`. Nothing is exposed outside.
- `OLLAMA_KEEP_ALIVE=-1` keeps the model loaded. Without it, the first question after a pause
  takes tens of seconds on a CPU-only box.
- A host firewall must let the containers in:
  `sudo ufw allow from 172.16.0.0/12 to any port 11434 proto tcp`.
- macOS and Windows (Docker Desktop) need none of this.

## Network & access

| Item | Requirement |
|---|---|
| Ports | `8080` (hub) and `8180` (Keycloak) must be **free on this machine**; `11434` is Ollama's own (Pro). The stack binds them loopback-only — remote access goes through an SSH tunnel or the [TLS proxy](../deploy.md#one-https-origin-the-tls-proxy) |
| Source flow *(real ingestion only)* | a network path hub → source database, and a **`SELECT`-only account** on the declared tables ([JDBC → Connection](../connectors/jdbc.md#connection)) — nothing for the guided path: the demo embeds its own sources |
| First-login accounts | nothing to prepare: the realm ships `hubadmin` and `integrator`, whose passwords land in the generated `.env` at install time ([Quickstart](installer.md)) |

## The 60-second self-check

```bash
docker run --rm hello-world                                    # 1. Docker
curl -sI https://github.com | head -1                          # 2. network
go version                                                     # 3. Go (source build only)
python3 --version && openssl version                           # 4. tools up.sh needs
curl -s http://localhost:11434/api/tags | jq -r '.models[].name'  # 5. Ollama (Pro)
df -h ~                                                        # 6. disk
```

| # | Expected |
|---|---|
| 1 | `Hello from Docker!` — daemon up, permission, registry reachable |
| 2 | `HTTP/2 200` — outbound network |
| 3 | `go1.26` or later |
| 4 | two version lines |
| 5 | lists `nomic-embed-text:latest`, `llama3.2:latest`, `qwen2.5-coder:7b` |
| 6 | ~20 GB free where you'll clone |

On a Linux host with the override above, ask the bridge instead:
`curl -s http://172.17.0.1:11434/api/tags`.

All green? Start the [guided path](../parcours.md) or go straight to
[Deploy](../deploy.md).
