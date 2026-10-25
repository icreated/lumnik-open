package io.lumnik.hub.connector.mapping.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Writes one RAG chunk per data row into {@code rag.chunk}.
 *
 * <p>Column mapping (actual schema from V1__rag_schema.sql):
 * <ul>
 *   <li>{@code source}   ← {@code spec.corpus()}
 *   <li>{@code type}     ← {@code "connector"} (fixed)
 *   <li>{@code module}   ← {@code sys._mapping}
 *   <li>{@code ref}      ← {@code sys._row_hash}
 *   <li>{@code content}  ← rendered chunk template
 *   <li>{@code metadata} ← spec metadata + {@code row_hash} for dedup
 *   <li>{@code embedding}← Ollama vector (via EmbeddingService)
 * </ul>
 *
 * <p>Dedup: if a chunk with the same {@code row_hash} in metadata already exists for this
 * source, the write is a no-op. The probe finds it by {@code ref}, which rag V6 CHECKs equal to
 * that {@code row_hash}. The whole ingestion chunk is probed with ONE query, and the
 * batch also dedups its own hashes (first occurrence wins) — the store probe cannot see a
 * duplicate that sits inside the same, not-yet-inserted batch.
 *
 * <p>Ollama tolerance: if an embedding sub-batch fails, its texts are retried one by one so
 * a single poison text costs one row — that chunk is skipped with a WARN log rather than
 * failing the whole import.
 */
@ApplicationScoped
public class RagWriter {

  private static final Logger LOG = Logger.getLogger(RagWriter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Texts per embedAll request. A modest constant on purpose: provider-friendly (local
  // Ollama and hosted APIs both take it comfortably) and it bounds a single request's
  // payload, so one oversized call never stalls or blows up the whole ingestion chunk.
  static final int SUB_BATCH = 128;

  private static final String INSERT_SQL =
      "INSERT INTO rag.chunk (id, tenant_id, connector_id, tags, source, type, module, ref, content, embedding, metadata) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), CAST(? AS jsonb))";

  // On ref, which this writer binds to the row hash: ->> is not leakproof, so under RLS it never
  // reaches an index (rag V6). The jsonb equality keeps a ref alone from counting as a row.
  static final String PROBE_SQL =
      "SELECT ref FROM rag.chunk WHERE source = ? AND ref = ANY(?) AND metadata->>'row_hash' = ref";

  @Inject ChunkTemplateRenderer renderer;
  @Inject EmbeddingService embeddings;
  @Inject EntityManager em;

  /**
   * Runs the {@link #prepare} probe in its own short transaction (the tenant GUC is set on
   * acquire, inside one). A field so a unit test, which has no Arc, can run the callable inline.
   */
  Function<Callable<Set<String>>, Set<String>> probeTx =
      probe -> QuarkusTransaction.requiringNew().call(probe);

  /** A chunk rendered and ready to dedup/embed/insert: one per surviving data row. */
  private record PendingChunk(String rowHash, String chunkText, String module, String metaJson) {}

  /** Chunks that made it through embedding, index-aligned with their vectors. */
  private record EmbeddedBatch(List<PendingChunk> chunks, List<float[]> vectors) {}

  /**
   * Write one chunk. Must be called within an active transaction.
   * Convenience wrapper over {@link #writeAll} for single-row callers.
   */
  public void write(MappingTarget.Rag spec, Map<String, Object> row,
                    Map<String, Object> sys, Long tenantId,
                    UUID connectorId, List<String> tags) {
    writeAll(spec, List.of(row), List.of(sys), tenantId, connectorId, tags);
  }

  /**
   * Write one chunk per data row, batched: one dedup probe for the whole batch,
   * {@code embedAll} in sub-batches, one batched INSERT. Must be called within an active
   * transaction — all store access runs on the caller's connection via {@code runWithConnection}.
   *
   * <p>Embeds inline (no prepared cache) — the pre-{@code prepare-hook} behaviour, unchanged.
   *
   * @param spec           the RAG target spec from the mapping
   * @param rows           data row values (column → value), one map per row
   * @param systemContexts per-row system context: {@code _row_hash}, {@code _mapping}, {@code _tenant}
   * @param tenantId       current tenant (stored in metadata)
   */
  public void writeAll(MappingTarget.Rag spec, List<Map<String, Object>> rows,
                       List<Map<String, Object>> systemContexts, Long tenantId,
                       UUID connectorId, List<String> tags) {
    writeAll(spec, rows, systemContexts, tenantId, connectorId, tags, null);
  }

  /**
   * Write one chunk per data row, batched, consuming a {@link #prepare} cache when one is
   * supplied. Must be called within an active transaction — all store access runs on the
   * caller's connection via {@code runWithConnection}.
   *
   * @param spec            the RAG target spec from the mapping
   * @param rows            data row values (column → value), one map per row
   * @param systemContexts  per-row system context: {@code _row_hash}, {@code _mapping}, {@code _tenant}
   * @param tenantId        current tenant (stored in metadata)
   * @param preparedVectors row_hash → vector computed ahead of the transaction by {@link #prepare};
   *                        {@code null} means "no prepare phase ran" — embed inline instead, the
   *                        original, backward-compatible behaviour. A fresh row absent from a
   *                        non-null map means its embedding failed in {@code prepare}: skipped
   *                        with a warn, same resilience as a live sub-batch failure.
   */
  public void writeAll(MappingTarget.Rag spec, List<Map<String, Object>> rows,
                       List<Map<String, Object>> systemContexts, Long tenantId,
                       UUID connectorId, List<String> tags, Map<String, float[]> preparedVectors) {
    String source = spec.corpus();

    List<PendingChunk> pending = renderChunks(spec, rows, systemContexts, tenantId);
    if (pending.isEmpty()) return;

    Set<String> existing = probeExistingHashes(source, pending);
    List<PendingChunk> fresh = pending.stream()
        .filter(p -> !existing.contains(p.rowHash())).toList();
    if (fresh.isEmpty()) return;

    EmbeddedBatch batch = (preparedVectors != null)
        ? useCachedVectors(fresh, preparedVectors, source)
        : embedInSubBatches(fresh, source);
    if (batch.chunks().isEmpty()) return;

    insertBatch(batch, source, tenantId, connectorId, tags);
  }

  /**
   * Prepare phase: render every chunk and embed the ones the store does not hold yet — network
   * plus ONE short read-only probe, meant to be called OUTSIDE the write transaction (the
   * {@code ItemWriter.prepare} hook). A run that re-reads rows the corpus already holds (a small
   * CSV re-read every run, a REST endpoint without a cursor, a cursor restart) would otherwise
   * pay one embedding call per row and then drop the vector (#953). The probe is the same
   * indexed lookup {@link #writeAll} runs, in its own transaction so the tenant GUC applies.
   *
   * <p>The probe in {@link #writeAll} stays the authority: a row absent here but inserted by a
   * concurrent run before the write is simply skipped there. A row present here but gone before
   * the write is missing from the returned cache and skipped with a warn, like a failed
   * embedding; the next run re-reads it. If the probe itself fails, every row is embedded, as
   * before: the probe saves money, it is never a gate.
   *
   * <p>Returns a plain value, not a cache on {@code this}: {@code RagWriter} is
   * {@code @ApplicationScoped} and reused across chunks and tenants, so nothing computed here is
   * ever stored on the bean — the caller (a fresh, per-chunk {@code ItemWriter}) is the only
   * place this result lives until {@link #writeAll} consumes it.
   *
   * @return row_hash → embedding vector for every chunk that survived render + embed; a row
   *         whose embedding failed even after the per-row fallback is simply absent.
   */
  public Map<String, float[]> prepare(MappingTarget.Rag spec, List<Map<String, Object>> rows,
                                      List<Map<String, Object>> systemContexts, Long tenantId) {
    List<PendingChunk> pending = renderChunks(spec, rows, systemContexts, tenantId);
    if (pending.isEmpty()) return Map.of();

    List<PendingChunk> fresh = withoutStored(pending, spec.corpus());
    if (fresh.isEmpty()) return Map.of();

    EmbeddedBatch batch = embedInSubBatches(fresh, spec.corpus());
    Map<String, float[]> vectors = new LinkedHashMap<>();
    for (int i = 0; i < batch.chunks().size(); i++) {
      vectors.put(batch.chunks().get(i).rowHash(), batch.vectors().get(i));
    }
    return vectors;
  }

  /** {@code pending} minus the rows the store holds; all of them if the probe cannot run. */
  private List<PendingChunk> withoutStored(List<PendingChunk> pending, String source) {
    try {
      Set<String> existing = probeTx.apply(() -> probeExistingHashes(source, pending));
      return pending.stream().filter(p -> !existing.contains(p.rowHash())).toList();
    } catch (Exception e) {
      LOG.warnf(e, "RagWriter: store probe failed before embedding (corpus=%s), embedding every row", source);
      return pending;
    }
  }

  /**
   * Consumes the {@link #prepare} cache in place of a live {@code embedAll} call. A fresh row
   * missing from the cache means its embedding genuinely failed during prepare (already retried
   * per-row there) — skipped here with the same warn-and-skip resilience as a live failure,
   * never re-attempted synchronously inside the write transaction.
   *
   * <p><b>Before you transform the rows again between the two phases, read this.</b> The cache is
   * keyed by {@code _row_hash}, computed from the TRANSFORMED row in both phases, so a lookup hits
   * only if the row written is the very row {@link #prepare} embedded. Two hooks are
   * content-nondeterministic — {@code to-uuid} with {@code generate: true} and
   * {@code enrich-context} with {@code ${now}} — and every cell hook that rewrites a value changes
   * the row when it runs again. {@code MappingEngine.writePrepared} writes exactly the rows
   * {@code MappingEngine.prepare} transformed (#888), which makes the hashes agree by construction;
   * transform a second time and those rows miss here and are skipped — a warn line is all you
   * would see. Pinned by {@code MappingEngineItemWriterPrepareClosedIT}'s rewriting-hook case and
   * {@code CellErrorDrainTest}'s shared-UUID guard.
   */
  private EmbeddedBatch useCachedVectors(List<PendingChunk> fresh, Map<String, float[]> preparedVectors,
                                         String source) {
    List<PendingChunk> embedded = new ArrayList<>(fresh.size());
    List<float[]> vectors = new ArrayList<>(fresh.size());
    for (PendingChunk p : fresh) {
      float[] vec = preparedVectors.get(p.rowHash());
      if (vec == null) {
        LOG.warnf("RagWriter: no prepared embedding for row_hash=%s (corpus=%s), chunk skipped",
            p.rowHash(), source);
        continue;
      }
      embedded.add(p);
      vectors.add(vec);
    }
    return new EmbeddedBatch(embedded, vectors);
  }

  /**
   * Render every chunk up front. Blank chunks are skipped, and a duplicate row_hash INSIDE
   * the batch keeps its first occurrence only — the sequential path got that for free (the
   * second probe saw the first insert); a batched probe checks the store once, so the batch
   * must dedup itself or the INSERT would double-insert what the probe missed.
   */
  private List<PendingChunk> renderChunks(MappingTarget.Rag spec, List<Map<String, Object>> rows,
                                          List<Map<String, Object>> systemContexts, Long tenantId) {
    List<PendingChunk> pending = new ArrayList<>(rows.size());
    Set<String> seenInBatch = new HashSet<>();
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> row = rows.get(i);
      Map<String, Object> sys = systemContexts.get(i);
      String rowHash = (String) sys.get("_row_hash");
      if (rowHash == null) throw new IllegalStateException("RagWriter requires _row_hash in sys context");

      String chunkText = renderer.render(spec.chunkTemplate(), row, sys);
      if (chunkText.isBlank()) continue;
      if (!seenInBatch.add(rowHash)) continue;

      Map<String, Object> meta = new LinkedHashMap<>();
      if (spec.metadata() != null) {
        spec.metadata().forEach((k, v) -> meta.put(k, renderer.render(v, row, sys)));
      }
      meta.put("row_hash", rowHash);
      if (tenantId != null) meta.put("tenant_id", tenantId.toString());

      String metaJson;
      try {
        metaJson = MAPPER.writeValueAsString(meta);
      } catch (Exception e) {
        throw new RuntimeException("RagWriter: metadata serialization failed", e);
      }

      String module = sys.getOrDefault("_mapping", "connector").toString();
      pending.add(new PendingChunk(rowHash, chunkText, module, metaJson));
    }
    return pending;
  }

  /**
   * ONE dedup probe for the whole batch. The hash set binds as a real text[] — same
   * createArrayOf reasoning as the tags in {@link #insertBatch}. runWithConnection stays on the
   * session's connection, so the caller's transaction (and its RLS tenant) still applies.
   */
  private Set<String> probeExistingHashes(String source, List<PendingChunk> pending) {
    Set<String> existing = new HashSet<>();
    em.runWithConnection((Connection conn) -> {
      try (PreparedStatement ps = conn.prepareStatement(PROBE_SQL)) {
        ps.setString(1, source);
        ps.setArray(2, conn.createArrayOf("text",
            pending.stream().map(PendingChunk::rowHash).toArray(String[]::new)));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) existing.add(rs.getString(1));
        }
      }
    });
    return existing;
  }

  /**
   * Embed in sub-batches of {@link #SUB_BATCH}, outside runWithConnection — a network call
   * has no business holding the connection callback. If a sub-batch call throws, its texts are retried
   * one by one so one poison text costs one row, not the sub-batch — preserving the per-row
   * warn-and-skip resilience of the sequential path.
   */
  private EmbeddedBatch embedInSubBatches(List<PendingChunk> fresh, String source) {
    List<PendingChunk> embedded = new ArrayList<>(fresh.size());
    List<float[]> vectors = new ArrayList<>(fresh.size());
    for (int from = 0; from < fresh.size(); from += SUB_BATCH) {
      List<PendingChunk> sub = fresh.subList(from, Math.min(from + SUB_BATCH, fresh.size()));
      List<String> texts = sub.stream().map(PendingChunk::chunkText).toList();
      try {
        List<float[]> vecs = embeddings.embedAll(texts);
        // Contract check ("N texts → N vectors, same order") — a short answer would
        // silently pair rows with the wrong vectors in the INSERT.
        if (vecs == null || vecs.size() != texts.size()) {
          throw new IllegalStateException("EmbeddingPort returned "
              + (vecs == null ? "null" : vecs.size()) + " vectors for " + texts.size() + " texts");
        }
        embedded.addAll(sub);
        vectors.addAll(vecs);
      } catch (Exception e) {
        LOG.warnf(e, "RagWriter: batch embedding failed for %d texts (corpus=%s), retrying per row",
            texts.size(), source);
        embedOneByOne(sub, source, embedded, vectors);
      }
    }
    return new EmbeddedBatch(embedded, vectors);
  }

  /** Per-row fallback for a failed sub-batch: warn + skip each text that still fails. */
  private void embedOneByOne(List<PendingChunk> sub, String source,
                             List<PendingChunk> embedded, List<float[]> vectors) {
    for (PendingChunk p : sub) {
      try {
        vectors.add(embeddings.embed(p.chunkText()));
        embedded.add(p);
      } catch (Exception e) {
        LOG.warnf(e, "RagWriter: embedding failed for row_hash=%s (corpus=%s), chunk skipped",
            p.rowHash(), source);
      }
    }
  }

  /**
   * One batched INSERT for the chunk — the addBatch/executeBatch shape DynamicTableWriter uses.
   *
   * <p>Tags bind as a real text[] parameter (createArrayOf — the ChunkRetagService pattern),
   * never a hand-rolled "{a,b}" literal: a scope tag is free text, so a comma inside one
   * would be stored as two tags and a brace would corrupt the array. runWithConnection stays on the
   * session's connection, so the caller's transaction (and its RLS tenant) still applies.
   */
  private void insertBatch(EmbeddedBatch batch, String source, Long tenantId,
                           UUID connectorId, List<String> tags) {
    List<String> safeTags = (tags != null) ? tags : List.of();
    em.runWithConnection((Connection conn) -> {
      try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
        Array tagsArray = conn.createArrayOf("text", safeTags.toArray(new String[0]));
        for (int i = 0; i < batch.chunks().size(); i++) {
          PendingChunk p = batch.chunks().get(i);
          ps.setObject(1, UUID.randomUUID());
          if (tenantId != null) ps.setLong(2, tenantId); else ps.setNull(2, Types.BIGINT);
          if (connectorId != null) ps.setObject(3, connectorId); else ps.setNull(3, Types.OTHER);
          ps.setArray(4, tagsArray);
          ps.setString(5, source);
          ps.setString(6, "connector");
          ps.setString(7, p.module());
          ps.setString(8, p.rowHash());
          ps.setString(9, p.chunkText());
          ps.setString(10, buildVectorLiteral(batch.vectors().get(i)));
          ps.setString(11, p.metaJson());
          ps.addBatch();
        }
        ps.executeBatch();
      }
    });
  }

  private static String buildVectorLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(vector[i]);
    }
    sb.append(']');
    return sb.toString();
  }
}
