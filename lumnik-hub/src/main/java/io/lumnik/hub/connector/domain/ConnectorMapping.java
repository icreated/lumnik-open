package io.lumnik.hub.connector.domain;

import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "connector_mapping", schema = "connector")
@Getter
@Setter
public class ConnectorMapping extends TenantedEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(name = "target_entity", nullable = false, length = 240)
  private String targetEntity;

  @Column(name = "spec_format", nullable = false, length = 20)
  private String specFormat;

  @Column(name = "spec_content", nullable = false, columnDefinition = "text")
  private String specContent;

  @Column(nullable = false)
  private int version = 1;
}
