# Advanced provider configuration

> The property behind every knob on [Models](models.md), how to change a timeout, and the switch
> to OpenAI.

Everyday model swaps — which model does what, the env vars, checking a swap — are on
[Models](models.md). This page is for overriding what the env vars do not cover.

## Property mapping

| Env var | Property it feeds | What it drives |
|---|---|---|
| `OLLAMA_BASE_URL` | `quarkus.langchain4j.ollama.base-url` + `…ollama.analytic.base-url` + `…ollama.judge.base-url` | Where **all three** Ollama clients connect |
| `RAG_CHAT_MODEL` | `quarkus.langchain4j.ollama.chat-model.model-id` | Conversational RAG chat |
| `ASK_SQL_MODEL` | `quarkus.langchain4j.ollama.analytic.chat-model.model-id` | Text-to-SQL ask + view generation |
| `ASK_SQL_NUM_CTX` | `quarkus.langchain4j.ollama.analytic.chat-model.model-options.num-ctx` | Context window of the analytic model (default `32768`) |
| `ASK_SQL_NUM_PREDICT` | `quarkus.langchain4j.ollama.analytic.chat-model.num-predict` | Most tokens the analytic model may write per call (default `1024`) |
| `JUDGE_MODEL` | `quarkus.langchain4j.ollama.judge.chat-model.model-id` | Offline faithfulness eval |
| `RAG_EMBEDDING_MODEL` | `quarkus.langchain4j.ollama.embedding-model.model-id` | Chunk + query embeddings |
| `RAG_EMBEDDING_DIM` | `quarkus.langchain4j.pgvector.dimension` | Must match the model **and** the `VECTOR(768)` column |

Any property here can be overridden per deployment as a JVM system property or as its environment
variable: `quarkus.langchain4j.ollama.analytic.timeout` is
`QUARKUS_LANGCHAIN4J_OLLAMA_ANALYTIC_TIMEOUT`.

### Timeouts

All three clients time out at `120s`: `quarkus.langchain4j.ollama.timeout` (chat),
`…ollama.analytic.timeout`, `…ollama.judge.timeout`. Raise one for a slow model:

```bash
-Dquarkus.langchain4j.ollama.analytic.timeout=300s
```

As an environment variable (`QUARKUS_LANGCHAIN4J_OLLAMA_ANALYTIC_TIMEOUT=300s`), add it to the hub
service's `environment:` — the self-host compose forwards only the variables it lists.

A call that times out is reported, not retried. That single attempt and the `ASK_SQL_NUM_PREDICT`
bound are set on the Ollama analytic client; moving that client to another provider means setting
the equivalent limits there.

## Pointing at OpenAI

The OpenAI client is already in the hub. Switching means editing
`lumnik-llm-langchain/src/main/resources/application.properties` and rebuilding the hub image
(`up.sh --build`): the provider selectors are read at build time.

1. **Switch the provider selectors** you want on OpenAI:

    ```properties
    quarkus.langchain4j.chat-model.provider=openai
    quarkus.langchain4j.embedding-model.provider=openai
    quarkus.langchain4j.analytic.chat-model.provider=openai
    quarkus.langchain4j.judge.chat-model.provider=openai
    ```

    Providers can be mixed (OpenAI for chat, the local model for SQL). The shipped file does not set
    the first line: set it explicitly, to `ollama` or `openai`, whenever you edit the file.

2. **Configure the OpenAI client**: `quarkus.langchain4j.openai.api-key`, the
   `quarkus.langchain4j.openai.*-model.model-name` properties, and `…openai.analytic.*` /
   `…openai.judge.*` for the named models you moved. Set `temperature=0` on `analytic` and `judge`:
   OpenAI's default is `1.0`.

3. **Match the embedding dimension**: set `RAG_EMBEDDING_DIM` to the model's (1536 for
   `text-embedding-3-small`) and migrate the column, as on
   [Models → Embeddings](models.md#embeddings-model-and-dimension-travel-together).

4. **Update the labels** `lumnik.llm.chat.provider` (default `ollama`),
   `lumnik.llm.embedding.provider` (default `ollama-nomic`) and `lumnik.llm.embedding.dimension`
   (default `768`). They do not choose a model; they are what the hub reports. The model id in
   `lm rag status` follows the active provider on its own (`unknown` if OpenAI's `model-name` is
   unset).

## Traps

!!! warning "Named models inherit nothing"
    The `analytic` and `judge` clients inherit nothing from the default one: not `base-url`, not
    `timeout`, not `temperature`. The shipped file sets all three on each. A named model you add
    needs all three too:

    - without `base-url` it calls `localhost:11434` and fails when the hub runs in a container;
    - without `temperature` it answers, but not the same way twice.

## See also

- [Models — configuring the LLMs](models.md) — which model does what, and the env vars.
- [HTTP API — status and reindex](http-api.md#status-and-reindex-admin) — the live provider/model.
