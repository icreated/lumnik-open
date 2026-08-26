package io.lumnik.hub.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(schema = "core", name = "audit_outbox")
public class AuditOutboxRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_key", nullable = false)
    private String entityKey;

    @Column(name = "operation", nullable = false, length = 20)
    private String operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> changes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "dispatched", nullable = false)
    private boolean dispatched = false;

    protected AuditOutboxRow() {}

    public Long getId()             { return id; }
    public Long getTenantId()       { return tenantId; }
    public Long getActorId()        { return actorId; }
    public String getEntityType()   { return entityType; }
    public String getEntityKey()    { return entityKey; }
    public String getOperation()    { return operation; }
    public Map<String, Object> getChanges() { return changes; }
    public Instant getCreatedAt()   { return createdAt; }
    public boolean isDispatched()   { return dispatched; }
    public void markDispatched()    { this.dispatched = true; }
}
