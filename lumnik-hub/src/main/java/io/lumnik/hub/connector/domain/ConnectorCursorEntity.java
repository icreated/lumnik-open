package io.lumnik.hub.connector.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.domain.TenantedEntity;
import io.lumnik.hub.connector.persistence.converters.JsonNodeConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Mutability;
import io.lumnik.hub.connector.persistence.converters.ImmutableJsonNodePlan;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connector_cursor", schema = "connector")
@Getter
@Setter
public class ConnectorCursorEntity extends TenantedEntity {

  /**
   * Identity: the cursor is PER ENDPOINT (a connector may own many endpoints, each with its own
   * cursor — declarative multi-table manifest). Re-keyed from connector_id in V19.
   */
  @Id
  @Column(name = "endpoint_id", nullable = false)
  private UUID endpointId;

  /** Back-reference to the owning connector (no longer the identity). */
  @Column(name = "connector_id", nullable = false)
  private UUID connectorId;

  @Convert(converter = JsonNodeConverter.class)
  @Mutability(ImmutableJsonNodePlan.class)
  @Column(name = "cursor_value", nullable = false, columnDefinition = "jsonb")
  @ColumnTransformer(write = "?::jsonb", read = "cursor_value::text")
  private JsonNode cursorValue;

  @Column(name = "captured_at", nullable = false)
  private Instant capturedAt = Instant.now();

  /**
   * Optimistic-lock version. Hibernate adds {@code WHERE version = ?} on UPDATE and increments
   * it, so two concurrent runs writing the same cursor cannot lose an update / rewind it — the
   * second to commit fails with an optimistic-lock error. Defense-in-depth behind the
   * per-connector advisory lock in {@code ConnectorEngine}.
   */
  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
