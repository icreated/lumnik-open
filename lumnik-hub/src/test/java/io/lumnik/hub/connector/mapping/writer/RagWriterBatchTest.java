package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.llm.spi.EmbeddingPort;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Batch-shape specification for the RAG ingestion target: N rows through
 * {@link MultiTargetWriter} must cost O(N/128) embedding calls, ONE dedup probe and ONE
 * batched INSERT — not N of each. Pure unit test: writers are new-ed up with their
 * package-private fields set directly (no Arc, no Quarkus), the store is a JDBC double.
 */
class RagWriterBatchTest {

  // ---------------------------------------------------------------- doubles

  /**
   * Recording EmbeddingPort. Fail-closed: EVERY invocation is captured (including ones that
   * throw), so a call the assertions did not expect can never slip by. {@code embed()} is the
   * SPI default method, so single-text calls surface here as embedAll of size 1 — exactly how
   * the per-row production path reaches the port today.
   */
  static final class RecordingPort implements EmbeddingPort {
    final List<List<String>> calls = new ArrayList<>();
    final Set<String> poisonTexts = new HashSet<>();

    @Override public String name() { return "recording"; }
    @Override public int dimension() { return 3; }

    @Override public List<float[]> embedAll(List<String> texts) {
      calls.add(List.copyOf(texts));
      for (String t : texts) {
        if (poisonTexts.contains(t)) throw new RuntimeException("poison text: " + t);
      }
      return texts.stream().map(t -> new float[] {1f, 2f, 3f}).toList();
    }

    /** Texts across ALL invocations, failed attempts included. */
    int totalTextsSeen() { return calls.stream().mapToInt(List::size).sum(); }
  }

  /** One row as bound on the insert statement: ref/module/metadata/tags bind positions. */
  record InsertedRow(String rowHash, String module, String metaJson, List<String> tags) {}

  /**
   * The rag.chunk store as a JDBC double. The dedup SELECT answers from {@code existingHashes}
   * intersected with the bound text[] (an honest probe); INSERTs record the bound row_hash
   * (bind position 8 = ref). Both the legacy per-row path (count(*) native query +
   * executeUpdate) and the batch path (SELECT ANY(?) + addBatch/executeBatch) are wired, so
   * the pre-batch code runs through cleanly and the red assertion fails on the COUNTER,
   * never on a mocking gap.
   */
  static final class StoreDouble {
    final Set<String> existingHashes = new HashSet<>();
    final List<InsertedRow> insertedRows = new ArrayList<>();
    String dedupSql;
    List<String> dedupBoundHashes;
    int executeBatchCount;
    int executeUpdateCount;

    List<String> insertedHashes() { return insertedRows.stream().map(InsertedRow::rowHash).toList(); }

    final EntityManager em;
    private final Map<Array, Object[]> arrayContents = new IdentityHashMap<>();
    // Current binds on the insert statement (committed on addBatch/executeUpdate):
    // 4 = tags text[], 7 = module, 8 = ref (row_hash), 11 = metadata jsonb.
    private List<String> currentTags;
    private String currentModule;
    private String currentBindHash;
    private String currentMeta;
    private int pendingBatchSize;

    StoreDouble() {
      try {
        this.em = build();
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    private EntityManager build() throws Exception {
      EntityManager em = mock(EntityManager.class);

      // Legacy per-row dedup: count(*) native query — answers from existing ∪ inserted,
      // mirroring what a live store would see mid-transaction (sequential semantics).
      Query countQuery = mock(Query.class);
      String[] boundHash = new String[1];
      when(countQuery.setParameter(anyInt(), any())).thenAnswer(inv -> {
        if ((Integer) inv.getArgument(0) == 2) boundHash[0] = (String) inv.getArgument(1);
        return countQuery;
      });
      when(countQuery.getSingleResult()).thenAnswer(inv ->
          (existingHashes.contains(boundHash[0]) || insertedHashes().contains(boundHash[0])) ? 1L : 0L);
      when(em.createNativeQuery(anyString())).thenReturn(countQuery);

      Connection conn = mock(Connection.class);
      when(conn.createArrayOf(eq("text"), any(Object[].class))).thenAnswer(inv -> {
        Array arr = mock(Array.class);
        arrayContents.put(arr, inv.getArgument(1));
        return arr;
      });

      // Batch dedup probe
      PreparedStatement selectPs = mock(PreparedStatement.class);
      doAnswer(inv -> {
        Object[] elems = arrayContents.get((Array) inv.getArgument(1));
        dedupBoundHashes = Arrays.stream(elems).map(String.class::cast).toList();
        return null;
      }).when(selectPs).setArray(eq(2), any());
      when(selectPs.executeQuery()).thenAnswer(inv -> {
        List<String> found = dedupBoundHashes.stream().filter(existingHashes::contains).toList();
        return resultSetOf(found);
      });

      // Insert (single-row executeUpdate AND addBatch/executeBatch both recorded)
      PreparedStatement insertPs = mock(PreparedStatement.class);
      doAnswer(inv -> {
        Object[] elems = arrayContents.get((Array) inv.getArgument(1));
        currentTags = Arrays.stream(elems).map(String.class::cast).toList();
        return null;
      }).when(insertPs).setArray(eq(4), any());
      doAnswer(inv -> {
        int pos = inv.getArgument(0);
        String val = inv.getArgument(1);
        switch (pos) {
          case 7 -> currentModule = val;
          case 8 -> currentBindHash = val;
          case 11 -> currentMeta = val;
          default -> { /* source, type, content, vector — not asserted */ }
        }
        return null;
      }).when(insertPs).setString(anyInt(), anyString());
      doAnswer(inv -> {
        insertedRows.add(new InsertedRow(currentBindHash, currentModule, currentMeta, currentTags));
        pendingBatchSize++;
        return null;
      }).when(insertPs).addBatch();
      when(insertPs.executeBatch()).thenAnswer(inv -> {
        executeBatchCount++;
        int[] counts = new int[pendingBatchSize];
        Arrays.fill(counts, 1);
        pendingBatchSize = 0;
        return counts;
      });
      when(insertPs.executeUpdate()).thenAnswer(inv -> {
        insertedRows.add(new InsertedRow(currentBindHash, currentModule, currentMeta, currentTags));
        executeUpdateCount++;
        return 1;
      });

      when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
        String sql = inv.getArgument(0);
        if (sql.startsWith("SELECT")) { dedupSql = sql; return selectPs; }
        return insertPs;
      });

      Session session = mock(Session.class);
      doAnswer(inv -> { ((Work) inv.getArgument(0)).execute(conn); return null; })
          .when(session).doWork(any());
      when(em.unwrap(Session.class)).thenReturn(session);
      return em;
    }

    private static ResultSet resultSetOf(List<String> values) throws Exception {
      ResultSet rs = mock(ResultSet.class);
      AtomicInteger i = new AtomicInteger(-1);
      when(rs.next()).thenAnswer(inv -> i.incrementAndGet() < values.size());
      when(rs.getString(1)).thenAnswer(inv -> values.get(i.get()));
      return rs;
    }
  }

  // ---------------------------------------------------------------- harness

  final RecordingPort port = new RecordingPort();
  final StoreDouble store = new StoreDouble();
  final RowHasher hasher = new RowHasher();
  final MultiTargetWriter writer = buildWriter();

  private MultiTargetWriter buildWriter() {
    @SuppressWarnings("unchecked")
    Instance<EmbeddingPort> ports = mock(Instance.class);
    when(ports.isUnsatisfied()).thenReturn(false);
    when(ports.get()).thenReturn(port);

    EmbeddingService embeddings = new EmbeddingService();
    embeddings.embeddingPorts = ports;

    RagWriter rag = new RagWriter();
    rag.renderer = new ChunkTemplateRenderer();
    rag.embeddings = embeddings;
    rag.em = store.em;

    MultiTargetWriter mtw = new MultiTargetWriter();
    mtw.ragWriter = rag;
    mtw.hasher = hasher;
    // tableWriter / hybridJsonbWriter stay null: the target below configures rag only.
    return mtw;
  }

  private static final MappingTarget RAG_ONLY = new MappingTarget(
      new MappingTarget.Rag("test-corpus", "Row ${name}", Map.of("entity", "test"), null),
      null, null, null);

  private void ingest(List<Map<String, Object>> rows) {
    writer.write(RAG_ONLY, rows, 1L, UUID.randomUUID(), "m1", 1, "src",
        UUID.randomUUID(), List.of("t1"));
  }

  private static List<Map<String, Object>> distinctRows(int n) {
    List<Map<String, Object>> rows = new ArrayList<>(n);
    for (int i = 0; i < n; i++) rows.add(Map.of("name", "n" + i));
    return rows;
  }

  // ------------------------------------------------------------------ tests

  @Test
  void ten_rows_cost_one_embedding_call_one_dedup_probe_one_batch() {
    ingest(distinctRows(10));

    // The batch contract: at most ⌈new/SUB_BATCH⌉ port invocations — one, for 10 rows.
    // Today's per-row loop makes 10 invocations of size 1; this is the red line.
    assertThat(port.calls).hasSizeLessThanOrEqualTo(1);
    assertThat(port.totalTextsSeen()).isEqualTo(10);
    assertThat(store.insertedHashes()).hasSize(10);

    // ONE dedup probe for the whole batch, binding all 10 hashes as a real text[].
    assertThat(store.dedupSql).contains("= ANY(?)");
    assertThat(store.dedupBoundHashes).hasSize(10);
    assertThat(store.executeBatchCount).isEqualTo(1);
    assertThat(store.executeUpdateCount).isZero();
  }

  @Test
  void store_duplicates_reach_neither_the_port_nor_the_insert() {
    List<Map<String, Object>> rows = distinctRows(10);
    String dup0 = hasher.hash(rows.get(0));
    String dup1 = hasher.hash(rows.get(1));
    store.existingHashes.add(dup0);
    store.existingHashes.add(dup1);

    ingest(rows);

    assertThat(port.calls).hasSizeLessThanOrEqualTo(1);
    assertThat(port.totalTextsSeen()).isEqualTo(8);
    assertThat(store.insertedHashes()).hasSize(8).doesNotContain(dup0, dup1);
  }

  @Test
  void intra_batch_duplicate_hash_inserts_once_first_occurrence_wins() {
    // Two identical rows hash identically. The sequential path deduped the second via the
    // store probe (the first was already inserted); the batch probe cannot see inside its
    // own batch, so the batch must dedup itself — or this double-inserts.
    Map<String, Object> twin = new HashMap<>(Map.of("name", "twin"));
    List<Map<String, Object>> rows = List.of(twin, Map.of("name", "other"), new HashMap<>(twin));

    ingest(rows);

    assertThat(store.insertedHashes()).hasSize(2).doesNotHaveDuplicates();
    assertThat(port.totalTextsSeen()).isEqualTo(2);
    assertThat(port.calls).hasSizeLessThanOrEqualTo(1);
  }

  @Test
  void one_poison_text_costs_one_row_not_the_sub_batch() {
    List<Map<String, Object>> rows = distinctRows(3);
    port.poisonTexts.add("Row n1");  // the rendered chunk text of row 1

    ingest(rows);

    // The sub-batch call throws, the per-row fallback retries individually: rows 0 and 2
    // still land, only the poison row is skipped — today's warn-and-skip resilience.
    assertThat(store.insertedHashes())
        .containsExactlyInAnyOrder(hasher.hash(rows.get(0)), hasher.hash(rows.get(2)));
  }

  @Test
  void blank_chunk_text_is_skipped_before_embedding() {
    // A bare ${name} template so the second row renders truly blank → skipped, per-row semantics.
    MappingTarget bareTemplate = new MappingTarget(
        new MappingTarget.Rag("test-corpus", "${name}", Map.of("entity", "test"), null),
        null, null, null);
    List<Map<String, Object>> rows = List.of(
        Map.of("name", "x"),
        Map.of("name", ""));

    writer.write(bareTemplate, rows, 1L, UUID.randomUUID(), "m1", 1, "src",
        UUID.randomUUID(), List.of("t1"));

    assertThat(port.totalTextsSeen()).isEqualTo(1);
    assertThat(store.insertedHashes()).hasSize(1);
  }

  @Test
  void metadata_shape_module_and_tags_are_preserved() {
    // The metadata JSON keeps spec keys + row_hash + tenant_id (in that order — LinkedHashMap),
    // module comes from _mapping, tags stay a real text[] — byte-identical to the per-row path.
    List<Map<String, Object>> rows = List.of(Map.of("name", "alice"));
    String hash = hasher.hash(rows.get(0));

    ingest(rows);

    assertThat(store.insertedRows).hasSize(1);
    InsertedRow row = store.insertedRows.get(0);
    assertThat(row.rowHash()).isEqualTo(hash);
    assertThat(row.module()).isEqualTo("m1");
    assertThat(row.metaJson())
        .isEqualTo("{\"entity\":\"test\",\"row_hash\":\"" + hash + "\",\"tenant_id\":\"1\"}");
    assertThat(row.tags()).containsExactly("t1");
  }

  // ------------------------------------------------------------ prepare-phase tests (Task 6)

  /**
   * The {@code prepare} phase (RagWriter.prepare, reached via MultiTargetWriter.prepare) embeds
   * ahead of the write; {@code write}'s {@code preparedVectors} parameter must then consume that
   * cache instead of embedding again — the port sees exactly one call, made during prepare.
   */
  @Test
  void prepare_then_write_consumes_the_cached_vectors_without_re_embedding() {
    List<Map<String, Object>> rows = distinctRows(5);
    UUID runId = UUID.randomUUID();
    UUID connectorId = UUID.randomUUID();

    Map<String, float[]> prepared = writer.prepare(RAG_ONLY, rows, 1L, runId, "m1", 1, "src");
    assertThat(port.calls).hasSize(1);
    assertThat(prepared).hasSize(5);

    writer.write(RAG_ONLY, rows, 1L, runId, "m1", 1, "src", connectorId, List.of("t1"),
        Map.of(), prepared);

    // No second embedding call: write() consumed the prepared cache.
    assertThat(port.calls).hasSize(1);
    assertThat(store.insertedHashes()).hasSize(5);
  }

  /**
   * A row absent from the prepared cache (its embedding failed during prepare, or prepare simply
   * never saw it) is skipped in write() with a warn — never re-embedded synchronously inside the
   * transaction, preserving the "no network call in the write tx" invariant even on the fallback
   * edge.
   */
  @Test
  void write_skips_a_fresh_row_missing_from_the_prepared_cache() {
    List<Map<String, Object>> rows = distinctRows(3);
    UUID runId = UUID.randomUUID();
    String survivorHash = hasher.hash(rows.get(1));

    // Simulate a partial prepare result: only row 1 made it into the cache.
    Map<String, float[]> partial = Map.of(survivorHash, new float[] {9f, 9f, 9f});

    writer.write(RAG_ONLY, rows, 1L, runId, "m1", 1, "src", UUID.randomUUID(), List.of("t1"),
        Map.of(), partial);

    assertThat(port.calls).isEmpty();
    assertThat(store.insertedHashes()).containsExactly(survivorHash);
  }

  /**
   * THE CROSS-CHUNK LEAKAGE GUARD: {@code RagWriter} is {@code @ApplicationScoped} — the SAME
   * bean instance serves every chunk of every tenant. Two consecutive "chunks" (two independent
   * prepare/write cycles through the one {@code writer} field built in {@link #buildWriter()})
   * must not let the second see the first's vectors. This holds by construction, not discipline:
   * {@link RagWriter#prepare} returns a fresh map on the stack and never assigns to a field on
   * {@code this}, so there is no bean-scoped storage for a later chunk to read.
   */
  @Test
  void two_chunks_through_the_same_rag_writer_bean_do_not_leak_prepared_vectors() {
    List<Map<String, Object>> chunk1Rows = List.of(Map.of("name", "alpha"));
    List<Map<String, Object>> chunk2Rows = List.of(Map.of("name", "beta"));
    String hash1 = hasher.hash(chunk1Rows.get(0));
    String hash2 = hasher.hash(chunk2Rows.get(0));

    Map<String, float[]> prepared1 = writer.prepare(RAG_ONLY, chunk1Rows, 1L, UUID.randomUUID(), "m1", 1, "src");
    assertThat(prepared1).containsOnlyKeys(hash1);

    // A second, independent chunk through the very same MultiTargetWriter/RagWriter beans.
    Map<String, float[]> prepared2 = writer.prepare(RAG_ONLY, chunk2Rows, 1L, UUID.randomUUID(), "m1", 1, "src");
    assertThat(prepared2)
        .as("the second chunk's prepared cache must contain only its own row, never the first chunk's")
        .containsOnlyKeys(hash2);

    // And the second chunk's write() must insert only its own row — chunk 1's vector never
    // resurfaces because chunk 2 was never handed it.
    writer.write(RAG_ONLY, chunk2Rows, 1L, UUID.randomUUID(), "m1", 1, "src", UUID.randomUUID(),
        List.of("t1"), Map.of(), prepared2);
    assertThat(store.insertedHashes()).containsExactly(hash2);
  }
}
