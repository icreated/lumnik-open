package io.lumnik.hub.connector.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.domain.TenantedEntity;
import io.lumnik.hub.sql.PgText;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Mutability;
import io.lumnik.hub.connector.persistence.converters.ImmutableJsonNodePlan;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connector_dead_letter", schema = "connector")
@Getter
@Setter
public class ConnectorDeadLetter extends TenantedEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "connector_id", nullable = false)
  private UUID connectorId;

  @Column(name = "endpoint_id")
  private UUID endpointId;

  @Column(name = "run_id")
  private UUID runId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Mutability(ImmutableJsonNodePlan.class)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode payload;

  @Column(name = "skip_reason", nullable = false, length = 40)
  private String skipReason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Mutability(ImmutableJsonNodePlan.class)
  @Column(name = "skip_detail", columnDefinition = "jsonb")
  private JsonNode skipDetail;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  @Column(nullable = false)
  private int attempts = 1;

  @Column(name = "captured_at", nullable = false)
  private Instant capturedAt = Instant.now();

  @Column(length = 20)
  private String resolution;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "resolved_by")
  private Long resolvedBy;

  @Column(name = "resolved_run_id")
  private UUID resolvedRunId;

  // What was dead-lettered may hold a NUL byte, which neither text nor jsonb stores (#1855): the letter would be lost
  // with the row it keeps.
  public void setPayload(JsonNode payload) { this.payload = PgText.storableJson(payload); }
  public void setSkipDetail(JsonNode skipDetail) { this.skipDetail = PgText.storableJson(skipDetail); }
  public void setErrorMessage(String errorMessage) { this.errorMessage = PgText.storable(errorMessage); }
}
