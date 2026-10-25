package io.lumnik.hub.event;

import io.lumnik.common.sql.Jdbc;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import javax.sql.DataSource;
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

    /**
     * The predicate of a reader that cannot skip (#1570): a row is read once its transaction is older
     * than the oldest one still open. An id is assigned at INSERT and visible at COMMIT, so a cursor on
     * id alone moves past a lower id that a long transaction commits later; below this horizon every
     * transaction is decided and no row can still appear. Evaluated in the reading statement's own
     * snapshot, so the horizon and the rows it lets through agree.
     */
    public static final String DECIDED = "xact_id < pg_snapshot_xmin(pg_current_snapshot())::text::bigint";

    /** {@link #DECIDED}'s horizon on its own: the oldest transaction still open, or the next one. */
    public static final String HORIZON = "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint";

    /**
     * A place in the log, in the order every reader follows: the inserting transaction, then the id
     * (V85). Rows written before V85 carry transaction 1.
     */
    public record Position(long xact, long id) implements Comparable<Position> {
        public static final Position ORIGIN = new Position(0, 0);

        @Override
        public int compareTo(Position o) {
            int byXact = Long.compare(xact, o.xact);
            return byXact != 0 ? byXact : Long.compare(id, o.id);
        }
    }

    /**
     * The tenant's last event in reading order, {@link Position#ORIGIN} when it has none — served by
     * {@code (tenant_id, xact_id, id)} (V85). A run's ending records it (#1351): a reader that has
     * reached it has read every row the run wrote.
     */
    public static Position highWater(DataSource ds, String label, long tenantId) {
        return Jdbc.queryOne(ds, label,
                "SELECT xact_id, id FROM core.event_outbox WHERE tenant_id = ? ORDER BY xact_id DESC, id DESC LIMIT 1",
                ps -> ps.setLong(1, tenantId), rs -> new Position(rs.getLong(1), rs.getLong(2)))
            .orElse(Position.ORIGIN);
    }

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

    /** The inserting transaction (V85): the database's default writes it, never the application. */
    @Column(name = "xact_id", insertable = false, updatable = false)
    private Long xactId;

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
    public Long getXactId()       { return xactId; }
}
