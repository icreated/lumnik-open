package io.lumnik.hub.connector.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.lumnik.hub.connector.api.types.ScriptVersion;
import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Mutability;
import io.lumnik.hub.connector.persistence.converters.ImmutableJsonNodePlan;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.stream.StreamSupport;

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

  // NULL while Queued (V68): set once, on leaving the queue.
  @Column(name = "started_at")
  private Instant startedAt = Instant.now();

  /** #952: when the run was accepted into the queue; NULL for a run that never queued. */
  @Column(name = "queued_at")
  private Instant queuedAt;

  /** #952: witness of the definition accepted with a queued run (see DefinitionHash). */
  @Column(name = "definition_sha256")
  private String definitionSha256;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "records_in", nullable = false)
  private long recordsIn = 0;

  @Column(name = "records_out", nullable = false)
  private long recordsOut = 0;

  @Column(name = "records_skipped", nullable = false)
  private long recordsSkipped = 0;

  /** Rows removed because the source no longer holds them (#1195): outside the conservation identity. */
  @Column(name = "records_removed", nullable = false)
  private long recordsRemoved = 0;

  /** Files not read because they had not changed since the run that read them to the end (#1371). */
  @Column(name = "files_unchanged", nullable = false)
  private long filesUnchanged = 0;

  /** Files read and not moved or removed as after_process declares: they stay in the source folder (#1382). */
  @Column(name = "files_not_archived", nullable = false)
  private long filesNotArchived = 0;

  /**
   * The outbox's high-water mark when the run ended (#1351): every row event ({@code data.row.changed})
   * the run wrote sits at or under it; its own {@code connector.run.*} event comes after. Null for a run
   * still writing, or one that ended before V76.
   */
  @Column(name = "outbox_high_water")
  private Long outboxHighWater;

  /**
   * The transaction half of that mark (V85): the outbox is read in {@code (xact_id, id)} order. Null for
   * a run that ended before V85, whose rows were all written at xact 1.
   */
  @Column(name = "outbox_high_water_xact")
  private Long outboxHighWaterXact;

  /**
   * The fingerprint of the tenant's processes when the run started (#1351): the run reads as evaluated
   * only while it is still theirs. Null for a run that started before V76.
   */
  @Column(name = "process_set")
  private String processSet;

  @Column(name = "cell_errors", nullable = false)
  private long cellErrors = 0;

  /** #748: the run never executed (skipped for a held endpoint lock, or the lock failed) — nothing to resolve. */
  @Column(name = "resolution_exempt", nullable = false)
  private boolean resolutionExempt;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount = 0;

  @JdbcTypeCode(SqlTypes.JSON)
  @Mutability(ImmutableJsonNodePlan.class)
  @Column(name = "cursor_before", columnDefinition = "jsonb")
  private JsonNode cursorBefore;

  @JdbcTypeCode(SqlTypes.JSON)
  @Mutability(ImmutableJsonNodePlan.class)
  @Column(name = "cursor_after", columnDefinition = "jsonb")
  private JsonNode cursorAfter;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "error_stack")
  private String errorStack;

  @Column(name = "triggered_by", nullable = false, length = 20)
  private String triggeredBy;

  @JdbcTypeCode(SqlTypes.JSON)
  @Mutability(ImmutableJsonNodePlan.class)
  @Column(name = "triggered_meta", columnDefinition = "jsonb")
  private JsonNode triggeredMeta;

  /**
   * #1430: each version of a Kotlin script the run's rows went through — {@code [{name, sha256}]}, once each, in the
   * order first run; null when none ran. Read and added to through {@link #scriptVersions}/{@link #recordScripts}.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Mutability(ImmutableJsonNodePlan.class)
  @Column(name = "scripts", columnDefinition = "jsonb")
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private JsonNode scripts;

  public List<ScriptVersion> scriptVersions() {
    if (scripts == null) return List.of();
    return StreamSupport.stream(scripts.spliterator(), false)
        .map(n -> new ScriptVersion(n.path("name").asText(), n.path("sha256").asText()))
        .toList();
  }

  /** Adds the versions this run had not recorded yet; a new node, so the immutable plan sees the change. */
  public void recordScripts(Collection<ScriptVersion> ran) {
    if (ran.isEmpty()) return;
    SequencedSet<ScriptVersion> all = new LinkedHashSet<>(scriptVersions());
    if (!all.addAll(ran)) return;
    ArrayNode node = JsonNodeFactory.instance.arrayNode();
    all.forEach(v -> node.addObject().put("name", v.name()).put("sha256", v.sha256()));
    scripts = node;
  }
}
