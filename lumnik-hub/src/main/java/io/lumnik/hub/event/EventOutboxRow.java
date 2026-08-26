package io.lumnik.hub.event;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable append-only event envelope (É2.a). All mutable delivery state lives
 * with consumers: cursors in core.event_consumer, retry in core.webhook_delivery.
 */
@Entity
@Table(schema = "core", name = "event_outbox")
public class EventOutboxRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "source", nullable = false, length = 120)
    private String source;

    @Column(name = "entity", length = 120)
    private String entity;

    @Column(name = "entity_key", length = 200)
    private String entityKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EventOutboxRow() {}

    /** Internal hub event: source "system", no entity identity, no dedup semantics. */
    public static EventOutboxRow of(Long tenantId, String eventType, Map<String, Object> payload) {
        return ofFull(tenantId, null, eventType, "system", null, null, payload, null, Instant.now());
    }

    /**
     * Full envelope — É2.b data events and É2.c inbound use this. The parameters backing
     * NOT NULL columns are enforced here so a null fails fast at the factory boundary with a
     * clear message, not later as an opaque constraint violation at flush (JPA inserts the
     * null explicitly, so the column DEFAULT never applies). org_id/entity/entity_key/dedup_key
     * are nullable columns and may be null.
     */
    // S107: the parameters mirror the outbox columns one-for-one (NOT NULL ones validated below,
    // the rest nullable). A row factory naming its columns is clearer here than a builder/param object.
    @SuppressWarnings("java:S107")
    public static EventOutboxRow ofFull(Long tenantId, Long orgId, String eventType, String source,
                                        String entity, String entityKey, Map<String, Object> payload,
                                        String dedupKey, Instant occurredAt) {
        EventOutboxRow r = new EventOutboxRow();
        r.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        r.orgId = orgId;
        r.eventType = Objects.requireNonNull(eventType, "eventType");
        r.source = Objects.requireNonNull(source, "source");
        r.entity = entity;
        r.entityKey = entityKey;
        r.payload = Objects.requireNonNull(payload, "payload");
        r.dedupKey = dedupKey;
        r.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        r.createdAt = Instant.now();
        return r;
    }

    public Long getId()           { return id; }
    public Long getTenantId()     { return tenantId; }
    public Long getOrgId()        { return orgId; }
    public String getEventType()  { return eventType; }
    public String getSource()     { return source; }
    public String getEntity()     { return entity; }
    public String getEntityKey()  { return entityKey; }
    public Map<String, Object> getPayload() { return payload; }
    public String getDedupKey()   { return dedupKey; }
    public Instant getOccurredAt(){ return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
