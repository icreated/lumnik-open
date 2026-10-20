# Models — configuring the LLMs

> Three separately-chosen chat models plus one embedding model, the exact env var for each, and
> the one number — the embedding dimension — that turns a model swap into a migration instead of
> a restart.

lumnik uses one model each for conversational chat, text-to-SQL, offline evaluation and
embeddings, each swappable through an environment variable. This page says which model does what,
the knobs, and how to swap one on Ollama. Property names and OpenAI are on
[Advanced provider configuration](providers-advanced.md).

The default provider is **Ollama, local**. Nothing leaves your machine unless you point it
elsewhere.

## Three chat models, on purpose

The three jobs need different models, and the judge must not share the generator's blind spots.

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

The answer is written from the first rows that fit **6000 characters** (about fifty catalogue rows);
the whole result stays in the answer — see
[A long result](../transparency/ask-honesty.md#a-long-result-what-the-answer-is-written-from). A wider
sample on a strong host, or a narrower one on a small model:

```
LUMNIK_ASK_ANSWER_BUDGET_CHARS=12000
```

The hub declares the analytic model's context at **32768 tokens**, so a long prompt is never silently truncated;
set `ASK_SQL_NUM_CTX` lower on a host short of memory.

- It writes **at most 1024 tokens** per call (`ASK_SQL_NUM_PREDICT`). A reply that reaches the bound is
  a failure and is never run. Raise it if you generate views or ask over very wide tables and meet it.
- A call that times out is reported, not retried.
- It runs at **temperature 0**, so the same question answers the same way in practice.

### Judge — faithfulness eval (`gemma4`)

The judge scores whether generated SQL faithfully answers the question — **offline/eval only,
never in the request path**.

| | |
|---|---|
| **Default model** | `gemma4` |
| **Independent from** | the analytic generator — kept distinct so the judge has different blind spots |
| **Why gemma4** | of four models compared, the lightest that reliably catches an INNER join where the question's "each X" implies LEFT, without over-flagging |
| **Production use** | optional — pull it only if you run the eval harness |
| **Override** | `JUDGE_MODEL` |

## Embeddings — model and dimension travel together

Semantic indexing and search embed text through one embedding model — default
`nomic-embed-text`, which produces **768-dimensional** vectors. That number is load-bearing:

- `RAG_EMBEDDING_DIM` (default `768`) must match the model;
- the `rag.chunk` table's `embedding` column is `VECTOR(768)`.

**A model with a different dimension is a migration, not an env-var flip.** No supported command
does it; rehearse the sequence on a copy first. Back up (`./deploy/selfhost/backup.sh`), then:

1. As the database owner, empty the chunks, change the column and rebuild the index:

    ```sql
    DELETE FROM rag.chunk;
    DROP INDEX rag.chunk_embedding_idx;
    ALTER TABLE rag.chunk ALTER COLUMN embedding TYPE VECTOR(<n>);
    CREATE INDEX chunk_embedding_idx ON rag.chunk USING hnsw (embedding vector_cosine_ops);
    ```

2. Set `RAG_EMBEDDING_MODEL` and `RAG_EMBEDDING_DIM` to match, and recreate the hub as in
   [Swapping a model](#swapping-a-model-on-ollama).
3. `lm rag reindex` for the platform corpus; re-run every connector, re-apply every
   `kind: Workflow` and re-ingest every `lm csv ingest` file, so their chunks are re-embedded.

## The knobs

All model choices are runtime environment variables. The self-host compose forwards them from the
root `.env` (template lines in `.env.example`); the Helm chart renders all nine from the `llm:`
values block (`deploy/helm/up.sh` fills it from `LUMNIK_`-prefixed variables). Unset means the
default applies.

| Env var | Default | What it drives |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Where **all three** Ollama clients connect |
| `RAG_CHAT_MODEL` | `llama3.2` | Conversational RAG chat |
| `ASK_SQL_MODEL` | `qwen2.5-coder:7b` | Text-to-SQL ask + view generation |
| `ASK_SQL_NUM_CTX` | `32768` | Context window of the analytic model, in tokens — see [Analytic](#analytic-text-to-sql-ask-qwen25-coder7b) |
| `ASK_SQL_NUM_PREDICT` | `1024` | Most tokens the analytic model may write per call (a SQL statement, a rewrite, an answer, a generated view) |
| `LUMNIK_ASK_ANSWER_BUDGET_CHARS` | `6000` | Characters of result rows the answer is written from; the rows beyond stay in the result |
| `JUDGE_MODEL` | `gemma4` | Offline faithfulness eval |
| `RAG_EMBEDDING_MODEL` | `nomic-embed-text` | Chunk + query embeddings |
| `RAG_EMBEDDING_DIM` | `768` | Must match the model **and** the `VECTOR(768)` column — see [above](#embeddings-model-and-dimension-travel-together) |

The self-host compose's own default for `OLLAMA_BASE_URL` is `http://host.docker.internal:11434`,
not `localhost` — the containerized hub reaches out to the host machine unless you set it; see
[Point the hub at an Ollama that is not on this machine](../deploy.md#point-the-hub-at-an-ollama-that-is-not-on-this-machine).

- Timeouts are `120s` for all three Ollama clients; to change one, see
  [Property mapping](providers-advanced.md#property-mapping).
- The analytic and judge temperatures are fixed at `0`; chat keeps the provider's default.
- `lm rag status` shows the **live** embedding provider and model. Chat and analytic models are
  checked in the hub's environment ([below](#swapping-a-model-on-ollama)).

## Switching the AI off

`LUMNIK_AI_ENABLED=false` turns off the chat, the ask, view generation and the embeddings, without
changing the build. Absent means on. How to set it: [Switch the AI off](../deploy.md#switch-the-ai-off).

It must be exactly `true` or `false`; anything else (`FALSE`, `no`, `0`) stops the hub at startup
with a message naming the setting.

What you see once it is off:

- **The PWA has no Chat tab.** The other tabs are untouched.
- **`lm module list`** shows `rag` and `llm` as `INACTIVE`, reason `disabled:lumnik.ai.enabled`.
  In the [Open edition](../editions.md) they are not listed at all.
- **`lm ask`, `lm rag search`, `lm scopes`** and a question in `lm chat` answer `404`.
- **`lm view generate`** answers that no chat model is available.
- **`lm rag status`** says `provider: none` and `indexed: false`; `lm rag reindex` has nothing to
  index with.
- **`lm csv ingest`** loads the file and no longer says it is askable.
- Nothing calls a model and no embedding is computed.

```console
$ lm ask --scope desk "combien de clients ?"
Error: API error 404 (no details from the server)
$ lm view generate desk "les clients"
Error: API error 400: generation abstained: no chat model is available on this hub — view generation needs one
$ lm rag status
indexed: false
chunks: 0
indexed_at: -
provider: none
embedding_model: none

$ lm module list
NAME      VERSION  SCHEMA              STATUS    REASON
core      0.1.0    Platform            ACTIVE
identity  0.1.0    Identity & Tenancy  ACTIVE
llm       0.1.0    Model provider      INACTIVE  disabled:lumnik.ai.enabled
rag       0.1.0    Platform RAG        INACTIVE  disabled:lumnik.ai.enabled
```

What does not change: the images, the `rag` schema and the data in it. Scope moves still re-tag
chunks (`lm connector scope sync`), a disabled or deleted workflow still leaves the corpus, and a
deleted connector's descriptor chunk still goes. Search does not depend on this setting.

**Switching it back on** brings back the data you had. What needed indexing while the AI was off (a
workflow applied, a table ingested, a connector re-described) is absent: re-apply the workflow or
the connector, or re-ingest the file.
`lm rag reindex` rebuilds only the platform corpus.

## Swapping a model on Ollama

Three steps, no rebuild:

1. Pull the model on the Ollama host: `ollama pull mistral`.
2. Add or update `RAG_CHAT_MODEL=mistral` in the root `.env` — an `echo >>` risks a duplicate
   definition if the key is already there.
3. Re-run `up.sh` with the flags you installed with, as for [Switch the AI off](../deploy.md#switch-the-ai-off).

Verify the swap took — a wrong variable name fails **silently** (the old model keeps answering):

```bash
docker compose -f docker-compose.selfhost.yml exec hub env | grep RAG_CHAT_MODEL
```

The same pattern applies to `ASK_SQL_MODEL`, `JUDGE_MODEL` and `RAG_EMBEDDING_MODEL` — with the
dimension caveat above for the embedding model. The three default pulls are documented once, in
[Prerequisites → The AI layer](../quickstart/prerequisites.md#the-ai-layer-ollama) — this page
doesn't duplicate them.

## Pointing at OpenAI

Ollama is the default. OpenAI means editing the module's configuration and rebuilding the hub image
(`up.sh --build`), not an env var, and changes the embedding provider (a migration, above). See
[Pointing at OpenAI](providers-advanced.md#pointing-at-openai).

## Traps

!!! warning "Changing the embedding dimension = migrate + re-index"
    `RAG_EMBEDDING_DIM` alone changes nothing on disk: the `rag.chunk` column stays `VECTOR(768)`.
    Follow [the migration steps](#embeddings-model-and-dimension-travel-together).

## See also

- [Prerequisites — the AI layer](../quickstart/prerequisites.md#the-ai-layer-ollama) — the three
  `ollama pull`s.
- [Point the hub at a remote Ollama](../deploy.md#point-the-hub-at-an-ollama-that-is-not-on-this-machine) —
  `OLLAMA_BASE_URL`, `.env` handling, the open-edition exception.
- [Ask honesty — the five guards](../transparency/ask-honesty.md) — what wraps the analytic
  model's output.
- [Scopes](scopes.md) — the boundary every model answers within.
- [Advanced provider configuration](providers-advanced.md) — property mapping, timeouts, OpenAI.
