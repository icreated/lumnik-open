package io.lumnik.hub.audit;

import io.lumnik.common.audit.Auditable;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises AuditEntityListener directly (not through a real Hibernate-mapped, @Auditable
 * production entity — Task 2 wires the first one of those on User/Secret and is where the
 * orm.xml global @PostPersist/@PostUpdate dispatch itself gets proven end-to-end). Here we call
 * afterInsert()/afterUpdate() the same way Hibernate would, with fixtures crafted to exercise the
 * three defects this task fixes: the missing actor, the UUID-id crash, and the fail-soft
 * boundary that must never let a capture failure reach the caller.
 */
@QuarkusTest
class AuditEntityListenerTest {

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private final AuditEntityListener listener = new AuditEntityListener();

    @BeforeEach
    void setTenant() {
        TenantContextFilter.CURRENT_TENANT.set(1L);
        CurrentTenantHolder.set(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContextFilter.CURRENT_TENANT.remove();
        TenantContextFilter.CURRENT_USER.remove();
        CurrentTenantHolder.clear();
    }

    @Auditable(fields = {"name"})
    static class LongIdEntity {
        final Long id;
        final String name;
        LongIdEntity(Long id, String name) { this.id = id; this.name = name; }
        public Long getId() { return id; }
        public String getName() { return name; }
    }

    @Auditable(fields = {"name"})
    static class UuidIdEntity {
        final UUID id;
        final String name;
        UuidIdEntity(UUID id, String name) { this.id = id; this.name = name; }
        public UUID getId() { return id; }
        public String getName() { return name; }
    }

    /** getId() throws — simulates a snapshot-capture failure without depending on reflection
     *  quirks elsewhere in the listener. */
    @Auditable(fields = {"name"})
    static class ThrowingIdEntity {
        public Object getId() { throw new IllegalStateException("simulated capture failure"); }
        public String getName() { return "boom"; }
    }

    /**
     * A simple class name deliberately over 100 characters — {@code core.audit_outbox.entity_type}
     * is {@code VARCHAR(100) NOT NULL}, so the listener's own INSERT overflows it. Unlike
     * {@link ThrowingIdEntity} (whose failure happens in Java, before any SQL is attempted), this
     * fixture reaches PostgreSQL and fails there — a genuine SQL-level error (F2), not a
     * pre-SQL capture failure.
     */
    @Auditable(fields = {"name"})
    static class AbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghij {
        final Long id;
        AbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghij(Long id) { this.id = id; }
        public Long getId() { return id; }
        public String getName() { return "irrelevant"; }
    }

    @Test
    void long_id_entity_produces_a_row_whose_key_is_the_id_as_text() throws Exception {
        Long id = System.nanoTime();

        utx.begin();
        em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
        listener.afterInsert(new LongIdEntity(id, "acme"));
        utx.commit();

        AuditOutboxRow row = findRow("LongIdEntity", String.valueOf(id));
        assertNotNull(row, "expected an audit_outbox row for the Long-id entity");
        assertEquals(String.valueOf(id), row.getEntityKey());
    }

    @Test
    void uuid_id_entity_is_not_silently_skipped() throws Exception {
        UUID id = UUID.randomUUID();

        utx.begin();
        em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
        listener.afterInsert(new UuidIdEntity(id, "prod-api-key"));
        utx.commit();

        // Before the fix: invokeGetter(entity, "getId", Long.class) ClassCastExceptions on a
        // UUID, the fail-soft catch swallows it, and no row is ever written — "audited" while
        // recording nothing.
        AuditOutboxRow row = findRow("UuidIdEntity", id.toString());
        assertNotNull(row, "a UUID id must produce a row, not be swallowed by the fail-soft catch");
        assertEquals(id.toString(), row.getEntityKey());
    }

    @Test
    void row_carries_the_current_user_as_actor() throws Exception {
        Long id = System.nanoTime();
        TenantContextFilter.CURRENT_USER.set(42L);

        utx.begin();
        em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
        listener.afterInsert(new LongIdEntity(id, "acme"));
        utx.commit();

        AuditOutboxRow row = findRow("LongIdEntity", String.valueOf(id));
        assertNotNull(row);
        assertEquals(42L, row.getActorId(), "the row must name who made the change");
    }

    @Test
    void capture_failure_never_breaks_the_business_write_and_leaves_no_row() throws Exception {
        // Hibernate invokes afterInsert() SYNCHRONOUSLY as a @PostPersist callback during the
        // audited entity's own flush. If this call threw, Hibernate would propagate it and roll
        // back the surrounding (business) transaction. Not throwing IS the mechanism that lets
        // the caller's write commit — so "does not throw" is the load-bearing assertion here.
        utx.begin();
        em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
        assertDoesNotThrow(() -> listener.afterInsert(new ThrowingIdEntity()),
                "an audit capture failure must never escape the listener");
        utx.commit();

        AuditOutboxRow row = findRow("ThrowingIdEntity", null);
        assertNull(row, "a failed capture must not leave a partial audit row behind");
    }

    /**
     * F2. In PostgreSQL, any statement error aborts the WHOLE transaction — a swallowed
     * exception from the listener's own INSERT does not undo that; the connection stays
     * poisoned and the very next statement (including COMMIT) fails with
     * "current transaction is aborted". This is the real defect the fail-soft catch could not
     * see: it looks fail-soft (it never throws) but the business write it was protecting is
     * lost anyway. The fix wraps the listener's INSERT in a JDBC savepoint, so a SQL-level
     * failure rolls back only that statement and leaves the surrounding transaction usable.
     */
    @Test
    void sql_level_audit_insert_failure_does_not_poison_the_business_write() throws Exception {
        String marker = "SAVEPOINT-PROOF-" + System.nanoTime();

        utx.begin();
        em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();

        // The business write: a real row, on the SAME connection/transaction the listener's
        // (about-to-fail) audit INSERT runs on — this is exactly what F2 protects.
        em.createNativeQuery(
                "INSERT INTO core.event_outbox (tenant_id, event_type, payload) "
              + "VALUES (1, :type, '{}'::jsonb)")
            .setParameter("type", marker)
            .executeUpdate();

        // Force a genuine SQL-level failure inside the listener's own INSERT: entity_type is
        // VARCHAR(100) NOT NULL, and this fixture's simple class name is 110 characters —
        // PostgreSQL rejects the value at execute time, not before.
        assertDoesNotThrow(() -> listener.afterInsert(
                new AbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghijAbcdefghij(System.nanoTime())),
            "the fail-soft catch must still swallow a SQL-level failure, exactly as it does a "
              + "capture failure");

        // Pre-fix: PostgreSQL aborts the whole transaction on that unhandled error (25P02), so
        // this commit itself throws "current transaction is aborted" and the business row above
        // is lost too — the exact loss this branch set out to prevent. Post-fix: the savepoint
        // absorbs the audit failure, the connection is usable again, and this commit succeeds
        // with the business row intact.
        assertDoesNotThrow(utx::commit,
            "a SQL-level audit failure must not poison the surrounding business transaction");

        Number count = (Number) em.createNativeQuery(
                "SELECT count(*) FROM core.event_outbox WHERE event_type = :type")
            .setParameter("type", marker)
            .getSingleResult();
        assertEquals(1L, count.longValue(),
            "the business write made before the failing audit INSERT must still be committed");
    }

    private AuditOutboxRow findRow(String entityType, String entityKey) {
        String jpql = "select r from AuditOutboxRow r where r.entityType = :type"
                + (entityKey != null ? " and r.entityKey = :key" : "");
        var query = em.createQuery(jpql, AuditOutboxRow.class).setParameter("type", entityType);
        if (entityKey != null) query.setParameter("key", entityKey);
        List<AuditOutboxRow> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }
}
