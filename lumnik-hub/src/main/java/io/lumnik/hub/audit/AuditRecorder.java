package io.lumnik.hub.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.HexFormat;
import java.util.Map;

/**
 * Explicit audit capture for writes that bypass Hibernate's listeners — the third call site
 * of the shape {@code SecretResource.recordAudit} and {@code EntityReadResource.recordRead}
 * established, extracted as a bean so view/workflow applies (audit P4) don't copy the block
 * a third time. Same contract: a separate admin-datasource connection (never the caller's JTA
 * connection), fail-soft — a broken audit insert is logged with its stack and never poisons
 * the write it records.
 */
@ApplicationScoped
public class AuditRecorder {

    private static final Logger LOG = Logger.getLogger(AuditRecorder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject TransactionSynchronizationRegistry txSync;

    public void record(String entityType, String entityKey, String operation, Map<String, Object> snapshot) {
        Long tenantId = TenantContextFilter.current();
        if (tenantId == null) return; // no request tenant (scheduler/startup) — same guard as the listener
        record(tenantId, TenantContextFilter.currentUser(), entityType, entityKey, operation, snapshot);
    }

    /**
     * {@link #record(String, String, String, Map)} once the caller's transaction has COMMITTED — for a
     * door whose write Hibernate flushes at commit, where a constraint can still refuse it after the
     * door returned (#1193): the trail never shows a write that did not happen. Without a transaction
     * it records at once. Tenant and actor are read now, while the request's ThreadLocals hold them.
     */
    public void recordAfterCommit(String entityType, String entityKey, String operation, Map<String, Object> snapshot) {
        if (txSync.getTransactionKey() == null) {
            record(entityType, entityKey, operation, snapshot);
            return;
        }
        Long tenantId = TenantContextFilter.current();
        if (tenantId == null) return;
        Long actorId = TenantContextFilter.currentUser();
        txSync.registerInterposedSynchronization(new Synchronization() {
            @Override public void beforeCompletion() { /* nothing to flush */ }
            @Override public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) record(tenantId, actorId, entityType, entityKey, operation, snapshot);
            }
        });
    }

    /**
     * Explicit-identity variant for a caller that cannot rely on request ThreadLocals — a
     * {@code StreamingOutput} callback (the container's response filter clears them before that
     * callback runs; see {@code EntitySnapshotDownloadResource}) or a future scheduled run with no
     * request context at all. Same insert, same fail-soft contract; the only difference is where
     * tenant/actor come from.
     */
    public void record(long tenantId, Long actorId, String entityType, String entityKey,
                        String operation, Map<String, Object> snapshot) {
        if (tenantId <= 0) return; // no genuine tenant (e.g. an uninitialized 0) — same fail-soft posture as the null-tenant guard above
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.audit_outbox "
               + "(tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at) "
               + "VALUES (?, ?, ?, ?, ?, cast(? as jsonb), now())")) {
            ps.setLong(1, tenantId);
            if (actorId != null) ps.setLong(2, actorId); else ps.setNull(2, Types.BIGINT);
            ps.setString(3, entityType);
            ps.setString(4, entityKey);
            ps.setString(5, operation);
            ps.setString(6, MAPPER.writeValueAsString(snapshot));
            ps.executeUpdate();
        } catch (Exception e) {
            // Fail-soft by contract, but with the full throwable: this line is the only
            // diagnostic anyone will ever get for an audit that stopped recording.
            LOG.warnf(e, "Audit capture skipped for %s#%s", entityType, operation);
        }
    }

    /** Content fingerprint for manifests — lets an auditor prove WHICH yaml was applied. */
    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unhashable";
        }
    }
}
