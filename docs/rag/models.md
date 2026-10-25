# Models — configuring the LLMs

> **lumnik Pro.** Three models do three jobs: the chat, the ask, the embeddings. Each one is an
> environment variable. The default provider is **Ollama, on your machine**: nothing leaves it
> unless you point it elsewhere.

## Which model does what

| Job | Default | Variable | Notes |
|---|---|---|---|
| Chat: `lm chat`, answers from your indexed data | `llama3.2` | `RAG_CHAT_MODEL` | a conversational model; it writes no SQL |
| Ask: `lm ask`, the PWA's Chat, `lm view generate` | `qwen2.5-coder:7b` | `ASK_SQL_MODEL` | must write SQL: a coding model. ~4.7 GB |
| Embeddings: indexing and semantic search | `nomic-embed-text` | `RAG_EMBEDDING_MODEL` | its dimension is load-bearing: [below](#embeddings-model-and-dimension-travel-together) |

The three `ollama pull` lines: [Prerequisites](../quickstart/prerequisites.md#the-ai-layer-ollama).
`JUDGE_MODEL` (`gemma4`) serves an offline evaluation only: a running hub never calls it. Skip it.

## The knobs

All are environment variables. Self-host: set them in the root `.env`, then re-run `up.sh` with
your flags. Helm (cloud edition): the same names, prefixed `LUMNIK_` when you run `up.sh`.

| Variable | Default | Drives |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://host.docker.internal:11434` (self-host) | where the hub finds Ollama — [another machine](../deploy.md#point-the-hub-at-an-ollama-on-another-machine) |
| `RAG_CHAT_MODEL` | `llama3.2` | the chat |
| `ASK_SQL_MODEL` | `qwen2.5-coder:7b` | the ask and view generation |
| `ASK_SQL_NUM_CTX` | `32768` | the ask model's context, in tokens. Lower it on a host short of memory |
| `ASK_SQL_NUM_PREDICT` | `1024` | the most tokens the ask model writes per call. Raise it if view generation or wide tables hit it |
| `LUMNIK_ASK_ANSWER_BUDGET_CHARS` | `6000` | how many characters of rows the answer is written from (about fifty catalogue rows). Every row is still returned |
| `RAG_EMBEDDING_MODEL` | `nomic-embed-text` | the embeddings |
| `RAG_EMBEDDING_DIM` | `768` | must match the embedding model — change it only with [the migration](#embeddings-model-and-dimension-travel-together) |

- The ask model runs at temperature 0: the same question gets the same SQL, in practice.
- A call that takes more than 120 seconds is reported, not retried: [timeouts](#timeouts).
- `lm rag status` shows the embedding provider and model the hub really uses.

## Swapping a model on Ollama

1. Pull it on the Ollama host: `ollama pull mistral`.
2. Set `RAG_CHAT_MODEL=mistral` in the root `.env`. Edit the line if it exists: do not add a second.
3. Re-run `up.sh` with the flags you installed with.

A misspelt variable is ignored without a word, and the old model keeps answering. Check:

```bash
docker compose -f docker-compose.selfhost.yml exec hub env | grep RAG_CHAT_MODEL
```

The same for `ASK_SQL_MODEL`. For `RAG_EMBEDDING_MODEL`, see the dimension below.

## Switching the AI off

`LUMNIK_AI_ENABLED=false` turns off the chat, the ask, view generation and the embeddings, without
changing the build. Absent means on. How to set it: [Switch the AI off](../deploy.md#switch-the-ai-off).

It must be exactly `true` or `false`; anything else (`FALSE`, `no`, `0`) stops the hub at startup
with a message naming the setting.

What you see once it is off:

- **The PWA has no Chat tab.** The other tabs are untouched.
- **`lm module list`** shows `rag` and `llm` as `INACTIVE`, reason `disabled:lumnik.ai.enabled`.
  In the [Open edition](../editions.md) they are not listed at all.
- **`lm ask`, `lm rag search`, `lm scopes`** and a question in `lm chat` answer `Error: API error 404 (no details from the server)`.
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

What does not change: the images and the data already indexed. Search does not depend on this
setting.

**Switching it back on** brings back the data you had. What needed indexing while the AI was off (a
workflow applied, a table ingested, a connector re-described) is absent: re-apply the workflow or
the connector, or re-ingest the file.
`lm rag reindex` rebuilds only the platform corpus.

## Embeddings — model and dimension travel together

`nomic-embed-text` writes 768-number vectors, and the index stores 768. **A model with another
dimension is a migration, not a variable flip.** No `lm` command does it. Try it on a copy first.

1. Back up: `./deploy/selfhost/backup.sh`.
2. As the database owner, empty the index and give it the new size `<n>`:

    ```sql
    DELETE FROM rag.chunk;
    DROP INDEX rag.chunk_embedding_idx;
    ALTER TABLE rag.chunk ALTER COLUMN embedding TYPE VECTOR(<n>);
    CREATE INDEX chunk_embedding_idx ON rag.chunk USING hnsw (embedding vector_cosine_ops);
    ```

3. Set `RAG_EMBEDDING_MODEL` and `RAG_EMBEDDING_DIM=<n>` in `.env`, then re-run `up.sh`.
4. Index again: `lm rag reindex`, then run every connector, re-apply every workflow and reload
   every `lm csv ingest` file.

## Advanced

### Timeouts

Each model call times out at 120 seconds. Raise one for a slow model, in the hub's environment:

```bash
QUARKUS_LANGCHAIN4J_OLLAMA_ANALYTIC_TIMEOUT=300s     # the ask model; …_OLLAMA_TIMEOUT for the chat
```

The self-host compose passes only the variables it lists: add the line to the hub's
`environment:` in `docker-compose.selfhost.yml`.

### Pointing at OpenAI

Not a variable: the provider is chosen when the hub is built.

1. In `lumnik-llm-langchain/src/main/resources/application.properties`, switch the providers you
   want:

    ```properties
    quarkus.langchain4j.chat-model.provider=openai
    quarkus.langchain4j.embedding-model.provider=openai
    quarkus.langchain4j.analytic.chat-model.provider=openai
    ```

2. Set `quarkus.langchain4j.openai.api-key` and the model names
   (`quarkus.langchain4j.openai.*-model.model-name`, `…openai.analytic.*`). Set `temperature=0` on
   the analytic model: OpenAI's default is 1.0.
3. Rebuild: `./deploy/selfhost/up.sh --build`.
4. OpenAI's embeddings have another dimension (1536 for `text-embedding-3-small`):
   [migrate](#embeddings-model-and-dimension-travel-together).
5. Check with `lm rag status`: it names the provider and model in use.

You can mix providers: OpenAI for the chat, the local model for the SQL.

## See also

- [Prerequisites — the AI layer](../quickstart/prerequisites.md#the-ai-layer-ollama): the `ollama pull`s.
- [When Ask refuses](../transparency/ask-honesty.md): what wraps the ask model's output.
- [Scopes](scopes.md): the boundary every model answers within.
