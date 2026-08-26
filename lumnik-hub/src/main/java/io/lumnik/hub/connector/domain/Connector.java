package io.lumnik.hub.connector.domain;

import io.lumnik.common.domain.TenantedEntity;
import io.lumnik.hub.connector.api.types.SkipPolicy;
import io.lumnik.hub.connector.api.types.SyncMode;
import io.lumnik.hub.connector.persistence.converters.SyncModeConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "connector", schema = "connector")
@Getter
@Setter
public class Connector extends TenantedEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(name = "type_id", nullable = false, length = 120)
  private String typeId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> config = new HashMap<>();

  @Column(name = "credential_id")
  private UUID credentialId;

  @Column(name = "mapping_id")
  private UUID mappingId;

  @Column(length = 60)
  private String schedule;

  /** P10: execution window "HH:MM-HH:MM" (UTC); null = no fence. */
  @Column(name = "run_window", length = 11)
  private String runWindow;

  @Convert(converter = SyncModeConverter.class)
  @Column(name = "sync_mode", nullable = false, length = 20)
  private SyncMode syncMode = new SyncMode.Incremental();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "skip_policy", nullable = false, columnDefinition = "jsonb")
  private SkipPolicy skipPolicy = new SkipPolicy.SkipUpTo(100);

  @Column(nullable = false)
  private boolean enabled = true;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "tags", nullable = false, columnDefinition = "text[]")
  private List<String> tags = new ArrayList<>();
}
