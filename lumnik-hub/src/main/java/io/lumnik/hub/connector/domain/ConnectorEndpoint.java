package io.lumnik.hub.connector.domain;

import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "connector_endpoint", schema = "connector")
@Getter
@Setter
public class ConnectorEndpoint extends TenantedEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "connector_id", nullable = false, updatable = false)
    private UUID connectorId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "method", nullable = false)
    private String method = "GET";

    @Column(name = "path", nullable = false)
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "schedule")
    private String schedule;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_run_status")
    private String lastRunStatus;
}
