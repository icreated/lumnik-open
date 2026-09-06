# Models — configuring the LLMs

> Three separately-chosen chat models plus one embedding model, the exact env var and property
> each is wired to, and the one number — the embedding dimension — that turns a model swap into
> a migration instead of a restart.

Every natural-language feature in lumnik sits behind two small SPIs (`ChatPort` / `EmbeddingPort`
in `lumnik-llm-spi`); the langchain4j wiring is confined to one module, `lumnik-llm-langchain`.
This page is for the operator: which model does what, the exact knobs, and how to swap a model —
on Ollama or toward OpenAI.

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
`kind: View` manifests on `POST /api/views/generate`. It needs a code/SQL-capable model:
qwen2.5-coder is far better than llama3.2 at SQL over many columns. It is ~4.7 GB
(`ollama pull qwen2.5-coder:7b`). Override with `ASK_SQL_MODEL`.

It runs at **temperature 0**, deliberately: the same question asked twice renders the same SQL
and gets the same answer. Raising it makes answers unreproducible.

### Judge — faithfulness eval (`gemma4`)

The judge scores whether generated SQL faithfully answers the question — **offline/eval only,
never in the request path**.

| | |
|---|---|
| **Default model** | `gemma4` |
| **Independent from** | the analytic generator — kept distinct so the judge has different blind spots |
| **Why gemma4** | a controlled probe across gemma2/gemma4/qwen3.6/llama3.2 settled it as the lightest judge that reliably catches the JOIN-type failure mode (INNER where the question's "each X" implies LEFT) without over-flagging |
| **Evidence** | `docs/studies/2026-06-27-judge-join-type-blind-spot.md` — measured weights per model (gemma4 9.6 GB vs. qwen3.6 23.9 GB for no extra reliability), which model caught or missed the JOIN-type case across three reps each, gemma4's reasoning quoted verbatim, and the resulting `JUDGE_MODEL` change from gemma2 to gemma4 (PR #77) |
| **Production use** | optional — pull it only if you run the eval harness |
| **Override** | `JUDGE_MODEL` |

## Embeddings — model and dimension travel together

Semantic indexing and search embed text through one embedding model — default
`nomic-embed-text`, which produces **768-dimensional** vectors. That number is load-bearing:

- `RAG_EMBEDDING_DIM` (default `768`) feeds `quarkus.langchain4j.pgvector.dimension`;
- the `rag.chunk` table's column is created by Flyway as `VECTOR(768)`
  (`lumnik-rag/.../db/migration/rag/V1__rag_schema.sql`) — the app never auto-creates it
  (`create-table=false`, deliberately, to avoid an init race).

**Changing the embedding model to one with a different dimension is not an env-var flip.**
The vector column stays `VECTOR(768)` until you migrate it yourself, and every existing
embedding becomes incompatible — you must alter the column *and* re-index all chunks
(re-run the connectors so content is re-embedded). Plan it as a migration, not a restart.

## The knobs

All model choices are runtime environment variables, passed through on every deploy path:
the self-host compose forwards them from the root `.env` (commented template lines sit in
`.env.example`), and the Helm chart renders them from the `llm:` values block (`up.sh`
derives those from `LUMNIK_`-prefixed variables). Unset means the application defaults
below apply.

| Env var | Default | Property it feeds | What it drives |
|---|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | `quarkus.langchain4j.ollama.base-url` + `…ollama.analytic.base-url` + `…ollama.judge.base-url` | Where **all three** Ollama clients connect |
| `RAG_CHAT_MODEL` | `llama3.2` | `quarkus.langchain4j.ollama.chat-model.model-id` | Conversational RAG chat |
| `ASK_SQL_MODEL` | `qwen2.5-coder:7b` | `quarkus.langchain4j.ollama.analytic.chat-model.model-id` | Text-to-SQL ask + view generation |
| `JUDGE_MODEL` | `gemma4` | `quarkus.langchain4j.ollama.judge.chat-model.model-id` | Offline faithfulness eval |
| `RAG_EMBEDDING_MODEL` | `nomic-embed-text` | `quarkus.langchain4j.ollama.embedding-model.model-id` | Chunk + query embeddings |
| `RAG_EMBEDDING_DIM` | `768` | `quarkus.langchain4j.pgvector.dimension` | Must match the model **and** the `VECTOR(768)` column |

Timeouts are fixed at `120s` for all three clients in the module config
(`lumnik-llm-langchain/src/main/resources/application.properties`); like any Quarkus runtime
property they can be overridden per deployment if a slow model needs more. The same file fixes
the **analytic and judge temperatures at `0`**; the conversational chat model keeps the
provider's default.

Three further properties — `lumnik.llm.chat.provider` (default `ollama`),
`lumnik.llm.embedding.provider` (default `ollama-nomic`), `lumnik.llm.embedding.dimension`
(default `768`) — are **labels, not selectors**: they are what the ports *report* through the
SPI (`name()` / `dimension()`), they do not choose a model. If you rewire providers, update them
so the system describes itself truthfully. The **model id** in
[`GET /api/platform/rag/status`](http-api.md#status-and-reindex-admin) needs no such care: it is
read from the model property of the **active** provider (Ollama's `model-id`, or OpenAI's
`model-name` after a rebinding — `"unknown"` if that one is unset), so it follows a swap
automatically.

## Swapping a model on Ollama

Three steps, no rebuild:

```bash
ollama pull mistral                                      # 1. pull the model on the Ollama host
echo 'RAG_CHAT_MODEL=mistral' >> .env                    # 2. root .env (the compose reads it)
docker compose -f docker-compose.selfhost.yml up -d hub  # 3. recreate the hub
```

Verify the swap took — a wrong variable name fails **silently** (the old model keeps answering):

```bash
docker compose -f docker-compose.selfhost.yml exec hub env | grep RAG_CHAT_MODEL
```

The same pattern applies to `ASK_SQL_MODEL`, `JUDGE_MODEL` and `RAG_EMBEDDING_MODEL` — with the
dimension caveat above for the embedding model. The three default pulls (and the
`host.docker.internal` wiring for a containerized hub) are documented once, in the
[self-host guide](../deploy.md) — this page doesn't duplicate them.

## Pointing at OpenAI

The OpenAI extension is already on the hub's classpath (`quarkus-langchain4j-openai` in
`lumnik-llm-langchain/pom.xml`) — the seam is wired, but Ollama is the shipped, exercised
default. The procedure, from the module's own config:

1. **Switch the provider selectors** — these are **build-time** properties (the langchain4j
   build step must disambiguate when both extensions are on the classpath), so this step means
   editing `lumnik-llm-langchain/src/main/resources/application.properties` and rebuilding the
   hub image (`up.sh --build`), not flipping an env var:

   ```properties
   quarkus.langchain4j.embedding-model.provider=openai
   quarkus.langchain4j.analytic.chat-model.provider=openai
   quarkus.langchain4j.judge.chat-model.provider=openai
   ```

   The last two are per-named-model — you may also leave them on `ollama` and mix providers
   (e.g. OpenAI for chat, local qwen for SQL); that is exactly what named models are for.

2. **Configure the OpenAI client** — `quarkus.langchain4j.openai.api-key` and the
   `quarkus.langchain4j.openai.*-model.model-name` properties for chat/embedding (and
   `quarkus.langchain4j.openai.analytic.*` / `…judge.*` for the named models you switched).
   Carry `temperature=0` over for whichever of `analytic` / `judge` you move — it belongs to
   the client you switch to, and OpenAI's default is `1.0`.

3. **Match the embedding dimension** — set `RAG_EMBEDDING_DIM` to the OpenAI model's dimension
   (e.g. 1536 for `text-embedding-3-small`) **and** migrate the `VECTOR(768)` column + re-index,
   as described above. This is the step that makes an embedding-provider swap a real migration.

4. Update the `lumnik.llm.*.provider` labels so the ports report the truth.

## Traps

!!! warning "Named models do not inherit base-url, timeout or temperature"
    In quarkus-langchain4j, a named model (`analytic`, `judge`) inherits **nothing** from the
    default client — not `base-url`, not `timeout`, not `temperature`. lumnik's shipped config
    sets all three explicitly (`…ollama.analytic.*` and `…ollama.judge.*` follow
    `OLLAMA_BASE_URL`, each with `timeout=120s` and `temperature=0`). If you ever add another
    named model, repeat all three. A missing `base-url` breaks loudly — the model falls back to
    `localhost:11434` and fails wherever the hub runs in a container while Ollama is on the
    host. A missing `temperature` does not: the model answers, just not the same way twice.

!!! warning "Changing the embedding dimension = migrate + re-index"
    `RAG_EMBEDDING_DIM` only tells pgvector what to expect; the `rag.chunk` column is fixed at
    `VECTOR(768)` by Flyway. A different-dimension embedding model requires altering the column
    and re-embedding every chunk (re-run your connectors). There is no in-place swap.

## See also

- [Self-host guide](../deploy.md) — the three `ollama pull`s, `OLLAMA_BASE_URL` for a
  containerized hub, `.env` handling.
- [Ask honesty — the five guards](../transparency/ask-honesty.md) — what wraps the analytic
  model's output.
- [Scopes](scopes.md) — the boundary every model answers within.
