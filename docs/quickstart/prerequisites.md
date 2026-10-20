# Prerequisites — prepare the ground

> What must be ready **before the integrator arrives**, in one checklist.

This list serves the [guided parcours](../parcours.md) and any first self-host install.
Items marked *(real ingestion only)* are not needed for the demo/parcours itself.

!!! note "On Windows, start elsewhere"
    Follow [Deploy on Windows](../deploy-windows.md) **first** — it installs WSL2 (admin
    rights + a restart, exactly what to get done before the integrator arrives) and says
    which window each command below goes in. Then come back to this checklist.

## The machine

| Item | Requirement | Notes |
|---|---|---|
| Docker + Docker Compose v2 | required | a laptop or any cloud VM works |
| Sizing | a **4 vCPU / 16 GB / NVMe** VPS holds the full authenticated read mix cleanly at 50 concurrent users ([measured](../operations/sizing.md)) | the LLM runs on its own budget — see below |
| Free disk | **~20 GB** for a comfortable first install (images + models + data) | the models alone are most of it |
| Outbound network | required **during install**: github.com (clone), the container registries, ollama.com (models) | at runtime the AI layer is local by default ([Models](../rag/models.md)) |
| Air-gapped install | **not supported** | no image pre-load or mirror tooling ships |
| Go 1.26+ | **only to build `lm` from source** — pre-built archives are on every [release](https://github.com/icreated/lumnik-open/releases/latest) | |
| python3 | for the demo's source stubs (`fake-siebel.py`) | parcours/demo only |

## The AI layer (Ollama)

**lumnik Pro only** — the open edition does not use Ollama; skip this section.
Chat, semantic search and ask need an Ollama the hub can reach. **Install
[Ollama](https://ollama.com) on the host** (not in a container), then pull the three models:

```bash
ollama pull nomic-embed-text      # embeddings
ollama pull llama3.2              # conversational chat
ollama pull qwen2.5-coder:7b     # text-to-SQL ask (4.7 GB — the analytic model)
```

Budget **~8 GB of downloads**. Three traps:

- **Linux host:** `host.docker.internal` resolves to the Docker **bridge**, and Ollama's
  systemd service binds `127.0.0.1` — the hub cannot reach it as installed. The fix (bind
  on the bridge IP, container-only) is in the self-host README, transcluded on
  [Deploy](../deploy.md#self-host-one-command).
- **CPU-only box:** works — set `OLLAMA_KEEP_ALIVE=-1` so the model stays loaded, or the
  first question after idle takes tens of seconds (same README section).
- **No GPU is required** for the parcours; size real model capacity separately, on the
  model's own hardware ([what sizing deliberately doesn't measure](../operations/sizing.md)).

## Network & access

| Item | Requirement |
|---|---|
| Ports | `8080` (hub) and `8180` (Keycloak) must be **free on this machine**; `11434` is Ollama's own (Pro). The stack binds them loopback-only — remote access goes through an SSH tunnel or the [TLS façade](../deploy.md#one-https-origin-the-tls-facade) |
| Source flow *(real ingestion only)* | a network path hub → source database, and a **`SELECT`-only account** on the declared tables ([JDBC → Connection](../connectors/jdbc.md#connection)) — nothing for the parcours: the demo embeds its own sources |
| First-login accounts | nothing to prepare: the realm ships `hubadmin` and `integrator`, whose passwords land in the generated `.env` at install time ([Quickstart](installer.md)) |

## The 60-second self-check

```bash
docker run --rm hello-world                                    # 1. Docker
curl -sI https://github.com | head -1                          # 2. network
go version                                                     # 3. Go (source build only)
python3 --version                                              # 4. Python
curl -s http://localhost:11434/api/tags | jq -r '.models[].name'  # 5. Ollama (Pro)
df -h ~                                                        # 6. disk
```

| # | Expected |
|---|---|
| 1 | `Hello from Docker!` — daemon up, permission, registry reachable |
| 2 | `HTTP/2 200` — outbound network |
| 3 | `go1.26` or later |
| 4 | any Python 3 — the demo stubs |
| 5 | lists `nomic-embed-text:latest`, `llama3.2:latest`, `qwen2.5-coder:7b` |
| 6 | ~20 GB free where you'll clone |

On a Linux host with the bridge fix above, Ollama listens on the bridge instead:
`curl -s http://172.17.0.1:11434/api/tags`.

All green? Start the [parcours](../parcours.md) or go straight to
[Deploy](../deploy.md).
