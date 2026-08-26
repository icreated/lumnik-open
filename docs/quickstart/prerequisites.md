# Prerequisites — prepare the ground

> The single checklist of what must be ready **before the integrator arrives** — every item
> in one place, because each forgotten one costs half a billed day.

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
| Outbound network | required **during install**: github.com (clone), the container registries, ollama.com (models) | there is **no air-gapped install path today** — no image pre-load or mirror tooling ships. Plan the install from a network that can reach out; at *runtime* the AI layer is local by default ([Models](../rag/models.md)) |
| Go 1.25+ | **required** — `lm` is built from source today; the floor is `lm/go.mod` | offline builds fail on older toolchains; `GOTOOLCHAIN=auto` masks this by silently downloading |
| python3 | for the demo's source stubs (`fake-siebel.py`) | parcours/demo only |

## The AI layer (Ollama)

Chat, semantic search and ask need an Ollama the hub can reach — without it, ingestion
and fusion still work but the language surfaces return nothing. **Install
[Ollama](https://ollama.com) on the host first** (not in a container), then pull the
three models, on the host too:

```bash
ollama pull nomic-embed-text      # embeddings
ollama pull llama3.2              # conversational chat
ollama pull qwen2.5-coder:7b     # text-to-SQL ask (4.7 GB — the analytic model)
```

Budget **~8 GB of downloads** for this step. Three traps, each already documented once:

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
| Ports | `8080` (hub), `8180` (Keycloak) and `11434` (Ollama) must be **free on this machine**. The stack binds them loopback-only — remote access goes through an SSH tunnel or the [TLS façade](../deploy.md#one-https-origin-the-tls-facade) |
| Source flow *(real ingestion only)* | a network path hub → source database, and a **`SELECT`-only account** on the declared tables ([JDBC → Connection](../connectors/jdbc.md#connection)) — nothing for the parcours: the demo embeds its own sources |
| First-login accounts | nothing to prepare: the realm ships `hubadmin` and `integrator`, whose passwords land in the generated `.env` at install time ([Quickstart](installer.md)) |

## The 60-second self-check

```bash
docker run --rm hello-world   # daemon up, you have permission, the registry is reachable
curl -sI https://github.com | head -1                   # outbound network (the proxy test)
go version                    # ≥ the version in lm/go.mod
python3 --version             # demo stubs
curl -s http://localhost:11434/api/tags                 # must list the three pulled models
df -h ~                       # ~20 GB free where you'll clone
```

All green? Start the [parcours](../parcours.md) or go straight to
[Deploy](../deploy.md).
