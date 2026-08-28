package io.lumnik.hub.connector.mapping.spec;

import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Discriminated union of write targets for a mapping.
 * Any combination of (rag, table, jpa, hybridJsonb) may be populated.
 *
 * <p>Legacy: a bare string target is interpreted as {@code jpa: { entity: ... }}.
 */
public record MappingTarget(
    Rag rag,
    Table table,
    Jpa jpa,
    HybridJsonb hybridJsonb
) {

  public record Rag(
      String corpus,
      String chunkTemplate,
      Map<String, String> metadata,
      String embeddingModel       // optional override
  ) {}

  public record Table(
      String schema,
      String name,
      boolean autoSchema,
      OnConflict onConflict,
      List<Index> indexes
  ) {}

  public record Jpa(String entity) {}

  public record Index(List<String> columns, boolean unique) {}

  public enum OnConflict { SKIP, UPDATE, ERROR }

  /**
   * Hybrid JSONB write target: promoted typed columns + raw JSONB payload,
   * stored in a tenant-isolated Postgres table managed by {@link io.lumnik.hub.connector.mapping.writer.HybridJsonbWriter}.
   *
   * @param table         Fully-qualified target table, e.g. {@code ext.stripe_charges}
   * @param uniqueKeyPath JsonPath to extract the external idempotency key from each item
   * @param promote       Columns to extract and store in typed Postgres columns
   * @param endpointId    REST endpoint lineage (stored in each row)
   */
  public record HybridJsonb(
      String table,
      String uniqueKeyPath,
      List<HybridJsonbSpec.PromotedColumn> promote,
      UUID endpointId
  ) {}

  public static MappingTarget jpaOnly(String entity) {
    return new MappingTarget(null, null, new Jpa(entity), null);
  }
}
