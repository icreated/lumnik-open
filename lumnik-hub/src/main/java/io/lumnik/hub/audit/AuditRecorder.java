package io.lumnik.hub.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    public void record(String entityType, String entityKey, String operation, Map<String, Object> snapshot) {
        Long tenantId = TenantContextFilter.current();
        if (tenantId == null) return; // no request tenant (scheduler/startup) — same guard as the listener
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.audit_outbox "
               + "(tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at) "
               + "VALUES (?, ?, ?, ?, ?, cast(? as jsonb), now())")) {
            ps.setLong(1, tenantId);
            Long actorId = TenantContextFilter.currentUser();
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
