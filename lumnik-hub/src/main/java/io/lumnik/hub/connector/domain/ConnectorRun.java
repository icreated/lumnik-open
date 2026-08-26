package io.lumnik.hub.connector.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connector_run", schema = "connector")
@Getter
@Setter
public class ConnectorRun extends TenantedEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "connector_id", nullable = false)
  private UUID connectorId;

  @Column(name = "endpoint_id")
  private UUID endpointId;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "sync_mode", nullable = false, length = 20)
  private String syncMode;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt = Instant.now();

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "records_in", nullable = false)
  private long recordsIn = 0;

  @Column(name = "records_out", nullable = false)
  private long recordsOut = 0;

  @Column(name = "records_skipped", nullable = false)
  private long recordsSkipped = 0;

  @Column(name = "cell_errors", nullable = false)
  private long cellErrors = 0;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount = 0;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "cursor_before", columnDefinition = "jsonb")
  private JsonNode cursorBefore;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "cursor_after", columnDefinition = "jsonb")
  private JsonNode cursorAfter;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "error_stack")
  private String errorStack;

  @Column(name = "triggered_by", nullable = false, length = 20)
  private String triggeredBy;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "triggered_meta", columnDefinition = "jsonb")
  private JsonNode triggeredMeta;
}
