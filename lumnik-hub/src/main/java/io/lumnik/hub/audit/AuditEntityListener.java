package io.lumnik.hub.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.audit.Auditable;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.arc.Arc;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global JPA entity listener registered via META-INF/orm.xml.
 * Fires after INSERT and UPDATE on any entity annotated with @Auditable.
 * Inserts one row into core.audit_outbox in the same transaction (transactional outbox pattern).
 * The insert runs inside a JDBC savepoint: a SQL-level failure there (missing table, disk full,
 * an RLS rejection) rolls back only the audit row, not the surrounding business transaction — see
 * {@link #insertOutboxRow} for why a bare {@code catch} alone could not guarantee that in
 * PostgreSQL.
 */
public class AuditEntityListener {

    private static final Logger LOG = Logger.getLogger(AuditEntityListener.class);

    @PostPersist
    public void afterInsert(Object entity) {
        handle(entity, "CREATE");
    }

    @PostUpdate
    public void afterUpdate(Object entity) {
        handle(entity, "UPDATE");
    }

    private void handle(Object entity, String operation) {
        Auditable ann = entity.getClass().getAnnotation(Auditable.class);
        if (ann == null) return;

        Long tenantId = TenantContextFilter.current();
        if (tenantId == null) return; // scheduler or Flyway context — skip

        try {
            // Whatever type the id is — Long, UUID, String — String.valueOf captures it. No
            // cast to Long: that cast is what used to silently drop every UUID-keyed entity
            // (e.g. Secret) through the fail-soft catch below.
            String entityKey = String.valueOf(invokeGetter(entity, "getId", Object.class));
            Map<String, Object> snapshot = captureFields(entity, ann.fields());

            insertOutboxRow(tenantId, TenantContextFilter.currentUser(),
                    entity.getClass().getSimpleName(), entityKey, operation, snapshot);
        } catch (Exception e) {
            // Never let audit failure break the business transaction. The throwable goes to the
            // logger, not just its message: this path is silent by design, so this line is the
            // only diagnostic anyone will ever get for a trail that quietly stopped recording —
            // and a wrapped SQLException without its stack is exactly what would hide the next
            // defect of the kind this branch spent its whole life finding.
            LOG.warnf(e, "Audit capture skipped for %s#%s",
                    entity.getClass().getSimpleName(), operation);
        }
    }

    private Map<String, Object> captureFields(Object entity, String[] fieldNames) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String field : fieldNames) {
            try {
                String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                Object value = entity.getClass().getMethod(getter).invoke(entity);
                // Store enum names as strings
                map.put(field, value instanceof Enum<?> e ? e.name() : value);
            } catch (NoSuchMethodException e) {
                // Try boolean getter: isXxx
                try {
                    String boolGetter = "is" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
                    Object value = entity.getClass().getMethod(boolGetter).invoke(entity);
                    map.put(field, value);
                } catch (Exception ignored) {
                    LOG.debugf("Cannot capture field '%s' on %s", field, entity.getClass().getSimpleName());
                }
            } catch (Exception e) {
                LOG.debugf("Cannot capture field '%s' on %s: %s", field, entity.getClass().getSimpleName(), e.getMessage());
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeGetter(Object entity, String methodName, Class<T> type) throws Exception {
        Method m = entity.getClass().getMethod(methodName);
        return (T) m.invoke(entity);
    }

    /**
     * Inserts the outbox row via a raw JDBC statement on the connection already backing the
     * current transaction — deliberately NOT {@code EntityManager#persist(AuditOutboxRow)}.
     * This callback fires mid-flush, from inside the audited entity's own
     * {@code @PostPersist}/{@code @PostUpdate} — i.e. from inside Hibernate's ActionQueue while it
     * is iterating the audited entity's own not-yet-completed insert/update action. Calling
     * {@code persist()} on the SAME Hibernate session from there mutates that same in-flight
     * ActionQueue: proven live by an end-to-end test that persisted a real {@code @Auditable}
     * entity — it duplicate-keyed on the audited entity's OWN primary key (IDENTITY generation
     * demanding an immediate nested flush) and, even after removing that generation strategy from
     * the equation, still threw a {@code ConcurrentModificationException} (a second entry added to
     * the very list Hibernate was mid-iteration over). Both failures marked the surrounding JTA
     * transaction rollback-only, so the *business* write — not just the audit row — was lost, in
     * direct violation of the fail-soft contract. A raw statement on the existing connection keeps
     * the row in the same transaction (it still rolls back together with the business write)
     * without touching Hibernate's persistence context at all, so there is nothing to reenter.
     */
    private void insertOutboxRow(Long tenantId, Long actorId, String entityType, String entityKey,
                                  String operation, Map<String, Object> changes) throws Exception {
        EntityManager em = Arc.container().instance(EntityManager.class).get();
        ObjectMapper mapper = Arc.container().instance(ObjectMapper.class).get();
        String changesJson = mapper.writeValueAsString(changes);

        em.runWithConnection((Connection connection) ->
            writeOutboxRow(connection, tenantId, actorId, entityType, entityKey, operation, changesJson));
    }

    /**
     * The savepoint-scoped INSERT itself, extracted from {@link #insertOutboxRow} so it can be
     * driven directly against a mocked {@link Connection} in a unit test — {@code runWithConnection}
     * needs a running persistence context.
     * Package-private for that reason.
     * <p>
     * In PostgreSQL any statement error aborts the WHOLE transaction, not just the failing
     * statement. Without a savepoint, the catch in {@link #handle} swallows the exception but the
     * connection stays poisoned: the business flush that follows dies with "25P02 current
     * transaction is aborted" — the fail-soft catch would be a lie. A savepoint scopes the damage
     * to this one INSERT: released on success, rolled back to on failure, leaving the surrounding
     * (business) transaction exactly as it was.
     * <p>
     * Two failure paths beyond the happy one, both handled the same way — attempt the rollback,
     * then rethrow with nothing lost: (1) the INSERT throws a {@link SQLException}: caught below,
     * rolled back, rethrown as itself. (2) anything else is thrown between {@code setSavepoint()}
     * and the INSERT completing (caught as {@code Exception}, matching {@link #handle}'s own
     * fail-soft breadth): the savepoint would otherwise be left neither released nor rolled back;
     * it is rolled back here too, and the original throwable is rethrown unwrapped if it is
     * itself a {@code SQLException} or {@code RuntimeException}, wrapped otherwise. If the
     * rollback ALSO throws, the original failure is not discarded — it is attached to the
     * rollback failure as a suppressed exception before that is thrown, so the fail-soft log line
     * in {@link #handle} still names the real cause instead of a secondary rollback error.
     */
    void writeOutboxRow(Connection connection, long tenantId, Long actorId, String entityType,
                         String entityKey, String operation, String changesJson) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO core.audit_outbox "
                  + "(tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at) "
                  + "VALUES (?, ?, ?, ?, ?, cast(? as jsonb), now())")) {
                ps.setLong(1, tenantId);
                if (actorId != null) ps.setLong(2, actorId); else ps.setNull(2, Types.BIGINT);
                ps.setString(3, entityType);
                ps.setString(4, entityKey);
                ps.setString(5, operation);
                ps.setString(6, changesJson);
                ps.executeUpdate();
            }
            connection.releaseSavepoint(savepoint);
        } catch (Exception e) {
            try {
                // The Agroal-pooled connection refuses a bare rollback(Savepoint) call while
                // enlisted in the surrounding JTA transaction (it cannot tell a savepoint-scoped
                // rollback from a full one, so it blocks both and flags the connection). A
                // savepoint rollback is a JDBC-level concept the transaction manager never sees —
                // it does not touch the enlistment — so unwrap to the real driver connection to
                // perform it; the connection and the savepoint are the same physical resource
                // either way.
                connection.unwrap(Connection.class).rollback(savepoint);
            } catch (SQLException rollbackFailure) {
                rollbackFailure.addSuppressed(e);
                throw rollbackFailure;
            }
            if (e instanceof SQLException se) throw se;
            if (e instanceof RuntimeException re) throw re;
            throw new SQLException("Audit outbox insert failed", e);
        }
    }
}
