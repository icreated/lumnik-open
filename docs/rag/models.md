# Models — configuring the LLMs

> Three separately-chosen chat models plus one embedding model, the exact env var for each, and
> the one number — the embedding dimension — that turns a model swap into a migration instead of
> a restart.

lumnik uses one model each for conversational chat, text-to-SQL, offline evaluation, and
embeddings — every one of them swappable through an environment variable. This page is for the
operator: which model does what, the exact knobs, and how to swap a model on Ollama. The provider
internals (SPIs, the exact Quarkus property each variable feeds, switching to OpenAI) live in
[Advanced provider configuration](providers-advanced.md).

The default provider is **Ollama, local**. Nothing leaves your machine unless you point it
elsewhere.

## Three chat models, on purpose

lumnik deliberately runs **three separately-chosen chat models**, because the three jobs have
different shapes — and because the judge must not share the generator's blind spots.

### Chat — conversational RAG (`llama3.2`)

The default chat model answers semantic questions over indexed chunks (`lm chat`,
`POST /api/rag/chat`). It is a general conversational model, chosen lighter/faster — it phrases answers from
retrieved context, it does not write SQL. Override with `RAG_CHAT_MODEL`.

### Analytic — text-to-SQL ask (`qwen2.5-coder:7b`)

The analytic model translates a question into one read-only SQL query
([the five guards](../transparency/ask-honesty.md) veto anything unsafe), and also writes
`kind: View` manifests on `POST /api/views/generate`. It needs a code/SQL-capable model —
a general conversational model is not one. It is ~4.7 GB (`ollama pull qwen2.5-coder:7b`).
Override with `ASK_SQL_MODEL`.

It runs at **temperature 0**: at the extension's inherited default (`0.8`), the same answerable
question abstained (`NO_ANSWER`) 11 times out of 40 draws; at `0`, 0 times out of 30. Temperature 0
buys greedy, low-variance decoding — not a guarantee of byte-identical SQL on every provider and
run, but it is what keeps this path answering the same way twice in practice.

### Judge — faithfulness eval (`gemma4`)

The judge scores whether generated SQL faithfully answers the question — **offline/eval only,
never in the request path**.

| | |
|---|---|
| **Default model** | `gemma4` |
| **Independent from** | the analytic generator — kept distinct so the judge has different blind spots |
| **Why gemma4** | of four models probed (gemma2/gemma4/qwen3.6/llama3.2), the lightest that reliably catches the JOIN-type failure mode (INNER where the question's "each X" implies LEFT) without over-flagging — measured, not guessed. Full comparison and verbatim reasoning: [Advanced → Why gemma4](providers-advanced.md#why-gemma4); study: `docs/studies/2026-06-27-judge-join-type-blind-spot.md` (PR #77) |
| **Production use** | optional — pull it only if you run the eval harness |
| **Override** | `JUDGE_MODEL` |

## Embeddings — model and dimension travel together

Semantic indexing and search embed text through one embedding model — default
`nomic-embed-text`, which produces **768-dimensional** vectors. That number is load-bearing:

- `RAG_EMBEDDING_DIM` (default `768`) feeds `quarkus.langchain4j.pgvector.dimension`;
- the `rag.chunk` table's column is created by Flyway as `VECTOR(768)`
  (`lumnik-rag/.../db/migration/rag/V1__rag_schema.sql`) — the app never auto-creates it
  (`create-table=false`, deliberately, to avoid an init race).

**Changing the embedding model to one with a different dimension is not an env-var flip**, and
there is currently no supported, tested migration command — this is the shape of what has to
happen, not a runbook:

1. The existing `VECTOR(768)` data cannot be cast to another dimension — there's no shrinking or
   widening a stored vector, so the old rows must be dropped, not converted.
2. The `chunk_embedding_idx` HNSW index is built on that column and must be dropped before
   `ALTER TABLE rag.chunk ALTER COLUMN embedding TYPE VECTOR(<n>)` can run, then rebuilt after.
3. Set `RAG_EMBEDDING_MODEL` and `RAG_EMBEDDING_DIM` to match, and restart the hub.
4. Rebuild the corpus: `POST /api/platform/rag/reindex`
   ([status and reindex](http-api.md#status-and-reindex-admin)) for the platform corpus, and
   re-run every connector so ingested-row chunks are re-embedded.

Back up first (`./deploy/selfhost/backup.sh`) — this touches every stored embedding and no
automated test covers the sequence above. Plan it as a migration, not a restart.

## The knobs

All model choices are runtime environment variables. The self-host compose forwards them from
the root `.env` (commented template lines sit in `.env.example`), and the Helm chart renders all
six from the `llm:` values block (`deploy/helm/up.sh` derives those from `LUMNIK_`-prefixed
variables). Unset means the application defaults below apply.

| Env var | Default | What it drives |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Where **all three** Ollama clients connect |
| `RAG_CHAT_MODEL` | `llama3.2` | Conversational RAG chat |
| `ASK_SQL_MODEL` | `qwen2.5-coder:7b` | Text-to-SQL ask + view generation |
| `JUDGE_MODEL` | `gemma4` | Offline faithfulness eval |
| `RAG_EMBEDDING_MODEL` | `nomic-embed-text` | Chunk + query embeddings |
| `RAG_EMBEDDING_DIM` | `768` | Must match the model **and** the `VECTOR(768)` column — see [above](#embeddings-model-and-dimension-travel-together) |

The self-host compose's own default for `OLLAMA_BASE_URL` is `http://host.docker.internal:11434`,
not `localhost` — the containerized hub reaches out to the host machine unless you set it; see
[Point the hub at an Ollama that is not on this machine](../deploy.md#point-the-hub-at-an-ollama-that-is-not-on-this-machine).

Timeouts default to `120s` for all three Ollama clients; the analytic and judge temperatures are
fixed at `0`, the conversational chat model keeps the provider's default. Verifying an
embedding-model swap: [`GET /api/platform/rag/status`](http-api.md#status-and-reindex-admin)
reads the **live** provider/model from the active port, so it reflects a swap immediately —
unlike chat or analytic, which you verify by checking the container's environment (below). The
exact Quarkus property each variable feeds, and how to override a timeout, live in
[Advanced provider configuration → Property mapping](providers-advanced.md#property-mapping).

## Swapping a model on Ollama

Three steps, no rebuild:

1. Pull the model on the Ollama host: `ollama pull mistral`.
2. Add or update `RAG_CHAT_MODEL=mistral` in the root `.env` — an `echo >>` risks a duplicate
   definition if the key is already there.
3. Recreate the hub: `docker compose -f docker-compose.selfhost.yml up -d hub`.

Verify the swap took — a wrong variable name fails **silently** (the old model keeps answering):

```bash
docker compose -f docker-compose.selfhost.yml exec hub env | grep RAG_CHAT_MODEL
```

The same pattern applies to `ASK_SQL_MODEL`, `JUDGE_MODEL` and `RAG_EMBEDDING_MODEL` — with the
dimension caveat above for the embedding model. The three default pulls are documented once, in
[Prerequisites → The AI layer](../quickstart/prerequisites.md#the-ai-layer-ollama) — this page
doesn't duplicate them.

## Pointing at OpenAI

The OpenAI extension is already on the hub's classpath — the seam is wired, but Ollama is the
shipped, exercised default. Switching means editing the module's config and rebuilding the hub
image (`up.sh --build`), not flipping an env var, and it makes an embedding-provider swap a real
migration (see above). Full procedure — including the one selector the shipped config leaves
implicit for the plain conversational chat model — lives in
[Advanced provider configuration → Pointing at OpenAI](providers-advanced.md#pointing-at-openai).

## Traps

!!! warning "Changing the embedding dimension = migrate + re-index"
    `RAG_EMBEDDING_DIM` only tells pgvector what to expect; the `rag.chunk` column is fixed at
    `VECTOR(768)` by Flyway. A different-dimension embedding model requires altering the column
    and re-embedding every chunk (re-run your connectors). There is no in-place swap.

## See also

- [Prerequisites — the AI layer](../quickstart/prerequisites.md#the-ai-layer-ollama) — the three
  `ollama pull`s.
- [Point the hub at a remote Ollama](../deploy.md#point-the-hub-at-an-ollama-that-is-not-on-this-machine) —
  `OLLAMA_BASE_URL`, `.env` handling, the open-edition exception.
- [Ask honesty — the five guards](../transparency/ask-honesty.md) — what wraps the analytic
  model's output.
- [Scopes](scopes.md) — the boundary every model answers within.
- [Advanced provider configuration](providers-advanced.md) — SPIs, full Quarkus property
  mapping, OpenAI end-to-end, named-model traps.
