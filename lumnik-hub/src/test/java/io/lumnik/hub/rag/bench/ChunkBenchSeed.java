package io.lumnik.hub.rag.bench;

import io.agroal.api.AgroalDataSource;
import io.lumnik.testkit.search.chunk.ChunkCorpus;
import io.lumnik.testkit.search.chunk.CorpusChunk;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the #675 corpus into the REAL {@code rag.chunk} table through the admin datasource (the table owner), with the
 * frozen vectors. {@code ref} carries the corpus chunk id and {@code source} the corpus name — exactly the columns
 * {@code RetrievalService.searchSql} reads and filters on.
 */
final class ChunkBenchSeed {

  private static final String INSERT =
      "INSERT INTO rag.chunk (id, tenant_id, source, type, ref, content, embedding, metadata, tags) "
          + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, CAST(? AS vector), CAST(? AS jsonb), ?)";

  private ChunkBenchSeed() {}

  static Set<Long> tenantsOf(ChunkCorpus corpus) {
    return corpus.chunks().stream().map(CorpusChunk::tenantId).collect(Collectors.toSet());
  }

  static void purge(AgroalDataSource admin, ChunkCorpus corpus) throws SQLException {
    try (Connection c = admin.getConnection();
         PreparedStatement ps = c.prepareStatement("DELETE FROM rag.chunk WHERE tenant_id = ANY (?)")) {
      Array tenants = c.createArrayOf("bigint", tenantsOf(corpus).toArray(new Long[0]));
      ps.setArray(1, tenants);
      ps.executeUpdate();
    }
  }

  static void seed(AgroalDataSource admin, ChunkCorpus corpus, Map<String, float[]> vectors) throws SQLException {
    purge(admin, corpus);
    try (Connection c = admin.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement ps = c.prepareStatement(INSERT)) {
        for (CorpusChunk chunk : corpus.chunks()) {
          ps.setLong(1, chunk.tenantId());
          ps.setString(2, chunk.corpus());
          ps.setString(3, chunk.type());
          ps.setString(4, chunk.id());
          ps.setString(5, chunk.text());
          ps.setString(6, vectorLiteral(vectors.get(chunk.id())));
          ps.setString(7, "workflow".equals(chunk.type()) ? "{}" : "{\"row_hash\":\"" + chunk.id() + "\"}");
          ps.setArray(8, c.createArrayOf("text", chunk.tags().toArray(new String[0])));
          ps.addBatch();
        }
        ps.executeBatch();
      }
      c.commit();
    }
    try (Connection c = admin.getConnection(); Statement st = c.createStatement()) {
      st.execute("ANALYZE rag.chunk");
    }
  }

  static String vectorLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(vector[i]);
    }
    return sb.append(']').toString();
  }
}
