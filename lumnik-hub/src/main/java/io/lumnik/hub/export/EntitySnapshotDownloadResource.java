package io.lumnik.hub.export;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.audit.AuditRecorder;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The lm_integrator-only one-shot entity CSV download door (#677). No export manifest, no
 * managed-run ledger entry — just a streamed snapshot, audited as data egress. See the design doc
 * for why the transaction/connection open inside {@link StreamingOutput#write} rather than at the
 * resource-method level, and why tenant/actor are captured here rather than read inside it.
 */
@Path("/api/exports/entities")
@RequiresPermission("lm_integrator")
public class EntitySnapshotDownloadResource {

    @Inject EntityRegistry registry;
    @Inject EntitySnapshotStreamer streamer;
    @Inject AuditRecorder auditRecorder;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;

    @ConfigProperty(name = "lumnik.export.snapshot.timeout-seconds", defaultValue = "1800")
    int timeoutSeconds;

    @GET
    @Path("/{name}/download")
    @Transactional // EntityRegistry.findReloadingIfMissing's cache-miss path reloads from the
                    // admin datasource — same @Transactional posture ExportApplyResource already
                    // takes around the identical call. This transaction covers only the
                    // resolve/scope-check phase below; it commits and closes well before
                    // StreamingOutput.write() runs, which opens its own via requiringNew().
    public Response download(@PathParam("name") String name) {
        long tenantId = RequireTenant.current();
        Long actorId = TenantContextFilter.currentUser();

        EntityManifest entity = registry.findReloadingIfMissing(name);
        if (entity == null) return ApiErrors.notFound("entity " + name + " not found");
        if (!ScopeGrants.allowsAny(entity.scopes())) return ApiErrors.scopeNotGranted();

        List<String> cols = entity.canonicalColumns();

        StreamingOutput out = os -> {
            SnapshotStats stats = QuarkusTransaction.requiringNew().timeout(timeoutSeconds).call(() -> {
                try (Connection c = readonlyDs.getConnection()) {
                    TenantRlsScope.applyTo(c, tenantId);
                    return streamer.stream(c, entity, cols, cols, ExportManifest.Format.DEFAULT, os);
                } catch (SQLException e) {
                    throw new RuntimeException("EntitySnapshotDownloadResource: " + e.getMessage(), e);
                }
            });
            recordEgress(tenantId, actorId, entity, stats);
        };

        return Response.ok(out)
            .header("Content-Disposition", "attachment; filename=\"" + entity.name() + ".csv\"")
            .header("Content-Type", "text/csv;charset=UTF-8")
            .build();
    }

    private void recordEgress(long tenantId, Long actorId, EntityManifest entity, SnapshotStats stats) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("entity", entity.name());
        changes.put("row_count", stats.rowCount());
        changes.put("byte_count", stats.byteCount());
        changes.put("sha256", stats.sha256Hex());
        auditRecorder.record(tenantId, actorId, entity.kind(), entity.name(), "EGRESS", changes);
    }
}
