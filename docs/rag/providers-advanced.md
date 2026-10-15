# Advanced provider configuration

> The SPI seam, the exact Quarkus property behind every knob on [Models](models.md), the full
> OpenAI switch, and the traps that only bite someone adding a provider or a named model.

This page is for adding a new named model, auditing the Quarkus wiring, or moving beyond the
shipped Ollama defaults. Everyday model swaps — which model does what, the env vars, verifying a
swap took — are on [Models](models.md).

## Architecture

Every natural-language feature in lumnik sits behind two small SPIs (`ChatPort` / `EmbeddingPort`
in `lumnik-llm-spi`); the langchain4j wiring is confined to one module, `lumnik-llm-langchain`.

Three chat-model slots exist in that module: the default (unnamed) client for conversational
chat, and two `@ModelName`-qualified clients, `analytic` and `judge`. quarkus-langchain4j treats
each slot as fully independent — a named client inherits nothing from the default, not even when
both point at the same provider (see [Traps](#traps) below).

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

These are runtime properties (unlike the provider selectors below, which are build-time).
Override any of them per deployment as a JVM system property or its matching environment
variable — MicroProfile Config maps `quarkus.langchain4j.ollama.analytic.timeout` to
`QUARKUS_LANGCHAIN4J_OLLAMA_ANALYTIC_TIMEOUT`, for example.

### Timeouts

Timeouts are fixed at `120s` for all three clients in the module config
(`lumnik-llm-langchain/src/main/resources/application.properties`):
`quarkus.langchain4j.ollama.timeout` (chat), `…ollama.analytic.timeout`, `…ollama.judge.timeout`.
Raise one if a slow model needs more, e.g.:

```bash
-Dquarkus.langchain4j.ollama.analytic.timeout=300s
```

A call that runs out of its timeout is **not** started again: it is reported once and you ask again. This single
attempt, and the output bound `ASK_SQL_NUM_PREDICT`, are set on the **Ollama** analytic model; if you rebind that
slot to another provider, set the equivalent retry and output-length limits there.

### Labels vs selectors

Three further properties — `lumnik.llm.chat.provider` (default `ollama`),
`lumnik.llm.embedding.provider` (default `ollama-nomic`), `lumnik.llm.embedding.dimension`
(default `768`) — are **labels, not selectors**: they are what the ports *report* through the
SPI (`name()` / `dimension()`), they do not choose a model. If you rewire providers, update them
so the system describes itself truthfully. The **model id** in
[`GET /api/platform/rag/status`](http-api.md#status-and-reindex-admin) needs no such care: it is
read from the model property of the **active** provider (Ollama's `model-id`, or OpenAI's
`model-name` after a rebinding — `"unknown"` if that one is unset), so it follows a swap
automatically.

## Pointing at OpenAI

The OpenAI extension is already on the hub's classpath (`quarkus-langchain4j-openai` in
`lumnik-llm-langchain/pom.xml`) — the seam is wired, but Ollama is the shipped, exercised
default. The procedure, from the module's own config:

1. **Switch the provider selectors** — these are **build-time** properties (the langchain4j
   build step must disambiguate when both extensions are on the classpath — they already are,
   see above), so this step means editing
   `lumnik-llm-langchain/src/main/resources/application.properties` and rebuilding the hub image
   (`up.sh --build`), not flipping an env var:

   ```properties
   quarkus.langchain4j.embedding-model.provider=openai
   quarkus.langchain4j.analytic.chat-model.provider=openai
   quarkus.langchain4j.judge.chat-model.provider=openai
   ```

   The last two are per-named-model — you may also leave them on `ollama` and mix providers
   (e.g. OpenAI for chat, local qwen for SQL); that is exactly what named models are for.

   If you also move the **default conversational chat model** to OpenAI, add a fourth selector.
   The shipped config never sets one explicitly for the default (unnamed) client — only the two
   named ones above — and that selector does exist:
   `quarkus.langchain4j.chat-model.provider=openai` is set in the module's own test config
   (`lumnik-llm-langchain/src/test/resources/application.properties`) but is absent from
   `src/main`. Leaving it unset while both extensions sit on the classpath risks the same
   build-time ambiguity the other three selectors exist to avoid — set it explicitly (`ollama` or
   `openai`) whenever you touch this file.

2. **Configure the OpenAI client** — `quarkus.langchain4j.openai.api-key` and the
   `quarkus.langchain4j.openai.*-model.model-name` properties for chat/embedding (and
   `quarkus.langchain4j.openai.analytic.*` / `…judge.*` for the named models you switched).
   Carry `temperature=0` over for whichever of `analytic` / `judge` you move — it belongs to
   the client you switch to, and OpenAI's default is `1.0`.

3. **Match the embedding dimension** — set `RAG_EMBEDDING_DIM` to the OpenAI model's dimension
   (e.g. 1536 for `text-embedding-3-small`) **and** migrate the `VECTOR(768)` column + re-index,
   as described on [Models → Embeddings](models.md#embeddings-model-and-dimension-travel-together).
   This is the step that makes an embedding-provider swap a real migration.

4. Update the `lumnik.llm.*.provider` labels so the ports report the truth.

## Why gemma4

The probe that chose the judge default: `docs/studies/2026-06-27-judge-join-type-blind-spot.md`
(PR #77). The trigger was a real terrain finding — asked to "list each customer and how many
orders they have", the analytic model generated an `INNER JOIN` (silently dropping customers with
zero orders), and the judge running at the time, `gemma2`, called that answer FAITHFUL. The
question's own wording — "**each** customer" — already implies every customer must appear; an
`INNER JOIN` violates that, and a judge can reach that conclusion purely from the question and the
SQL, with no data.

A controlled probe (`JudgeJoinProbeIT`, fixed SQL so the generator's own randomness can't
contaminate the result, 3 reps per case) measured four candidate judges on three cases — the
blind spot itself, a correctly-written `LEFT JOIN` that must read FAITHFUL, and a legitimate
`INNER JOIN` (a different question) that must also read FAITHFUL, so a fix can't trade a
false-negative for false-positives:

| Judge | Weight | Catches the blind spot | Correct LEFT reads FAITHFUL | Legitimate INNER reads FAITHFUL |
|---|---|---|---|---|
| gemma2 | 5.4 GB | ❌ misses it every time | ✅ | ✅ |
| **gemma4** | **9.6 GB** | ✅ every time | ✅ | ✅ |
| qwen3.6 | 23.9 GB | ✅ every time | ✅ | ✅ |
| llama3.2 | 2.0 GB | ⚠️ 1 out of 3 | ❌ misreads it as unfaithful | ❌ over-flags it too |

gemma4's reasoning, quoted verbatim from the probe: *"uses an INNER JOIN which excludes customers
who have placed zero orders; a LEFT JOIN is required to list every customer as requested."*

gemma4 is the lightest model that reliably catches the failure mode without over-flagging —
qwen3.6 matches it at 2.5× the weight, for no extra reliability, and llama3.2 is actively
dangerous as a judge (it over-flags the legitimate query and even misreads the correct one).
`JUDGE_MODEL` moved from `gemma2` to `gemma4` on the strength of this measurement, and
`JudgeJoinProbeIT` stays as the regression harness. This is a settled, measured choice — a probe
across real weights and real reasoning traces, not a guess or a stale-knowledge tag.

## Traps

!!! warning "Named models inherit nothing"
    In quarkus-langchain4j, a named model (`analytic`, `judge`) inherits **nothing** from the
    default client — not `base-url`, not `timeout`, not `temperature`. lumnik's shipped config
    sets all three explicitly (`…ollama.analytic.*` and `…ollama.judge.*` follow
    `OLLAMA_BASE_URL`, each with `timeout=120s` and `temperature=0`). If you ever add another
    named model, repeat all three. A missing `base-url` breaks loudly — the model falls back to
    `localhost:11434` and fails wherever the hub runs in a container while Ollama is on the
    host. A missing `temperature` does not: the model answers, just not the same way twice.

## See also

- [Models — configuring the LLMs](models.md) — the operator-facing page this one supports.
- [HTTP API — status and reindex](http-api.md#status-and-reindex-admin) — the live provider/model
  read-back.
