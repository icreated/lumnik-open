package io.lumnik.hub.api.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.secret.PgcryptoSecretResolver;
import io.lumnik.hub.secret.SecretEvents;
import io.lumnik.hub.secret.SecretRepository;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
@Path("/api/platform/secrets")
public class SecretResource {

    private static final Logger LOG = Logger.getLogger(SecretResource.class);

    @Inject SecretRepository repo;
    @Inject EntityManager em;
    @Inject PgcryptoSecretResolver resolver;
    @Inject SecretEvents events;
    @Inject ObjectMapper mapper;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @ConfigProperty(name = "lumnik.secret.master-key") String masterKey;

    @GET
    // Explicit floor (the annotation states the behavior): the platform filter already demands
    // lm_integrator on /api/platform/* — stated here so the gate survives any filter refactor.
    @RequiresPermission("lm_integrator")
    @Produces(MediaType.APPLICATION_JSON)
    // A READ needs the transaction as much as a write does, and for a reason a reader will not
    // guess: TenantConnectionInterceptor binds the tenant with SET LOCAL app.current_tenant, and
    // SET LOCAL is transaction-scoped. Without one it is discarded before the query, the policy's
    // NULLIF(…, '')::bigint becomes NULL, and tenant_id = NULL matches nothing. This method
    // answered "(no secrets)" on a hub whose table held them. SecretResourceRlsIT is the twin
    // that can see it — the plain @QuarkusTest one cannot, its datasource owning the table.
    @Transactional
    public List<SecretDto> list() {
        long tid = RequireTenant.current();
        return repo.listByTenant(tid).stream().map(SecretDto::from).toList();
    }

    @GET @Path("/{name}")
    @Transactional   // same reason as list(): SET LOCAL needs a transaction to survive
    @RequiresPermission("lm_integrator")
    @Produces(MediaType.APPLICATION_JSON)
    public Response describe(@PathParam("name") String name) {
        long tid = RequireTenant.current();
        return repo.findByName(tid, name)
            .map(s -> Response.ok(SecretDto.from(s)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    @RequiresPermission("lm_admin")
    public Response create(SecretCreateRequest req) {
        long tid = RequireTenant.current();
        if (req == null || req.name() == null || req.name().isBlank()
            || req.value() == null || req.value().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "name and value required")).build();
        }

        // Through ApiErrors, which sets the content type this door was missing: the CLI's create →
        // 409 → update fallback reads the status, but anything reading the body got it undressed.
        // A secret's handle is its name, so the refusal names it and the gesture that replaces its
        // value — never the value.
        if (repo.findByName(tid, req.name()).isPresent()) {
            return ApiErrors.conflict("secret " + req.name() + " already exists — lm secret set " + req.name()
                + " replaces its value");
        }

        // Encrypt via pgcrypto in-DB. The id is generated here (not gen_random_uuid() in SQL) so
        // the audit call below can key its row without a second round-trip.
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
            "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted, description) " +
            "VALUES (:id, :tid, 0, :name, " +
            "        pgp_sym_encrypt(:val, :key, 'cipher-algo=aes256'), :desc)")
            .setParameter("id", id)
            .setParameter("tid", tid)
            .setParameter("name", req.name())
            .setParameter("val", req.value())
            .setParameter("key", masterKey)
            .setParameter("desc", req.description())
            .executeUpdate();

        resolver.invalidate();   // ensure cache picks up new value next read
        events.emitCreated(tid, req.name(), null);
        recordAudit(tid, "CREATE", id.toString(), snapshot(req.name(), req.description(), null));
        return Response.status(Response.Status.CREATED)
            .entity(Map.of("name", req.name())).build();
    }

    @PUT @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @RequiresPermission("lm_admin")
    public Response update(@PathParam("name") String name,
                            SecretUpdateRequest req) {
        long tid = RequireTenant.current();
        var existing = repo.findByName(tid, name);
        if (existing.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
        Instant previousRotatedAt = existing.get().getRotatedAt();

        // rotatedAt is bound explicitly (not SQL now()) so the audit snapshot below can carry
        // the exact value written to the row, not an approximation of it.
        Instant rotatedAt = Instant.now();
        String newDescription = req.description() != null ? req.description() : existing.get().getDescription();

        int rows = em.createNativeQuery(
            "UPDATE platform.secret SET value_encrypted = pgp_sym_encrypt(:val, :key, 'cipher-algo=aes256'), " +
            "       updated_at = now(), rotated_at = :rotatedAt, description = COALESCE(:desc, description) " +
            "WHERE tenant_id = :tid AND name = :name AND deleted_at IS NULL")
            .setParameter("val", req.value())
            .setParameter("key", masterKey)
            .setParameter("rotatedAt", rotatedAt)
            .setParameter("desc", req.description())
            .setParameter("tid", tid)
            .setParameter("name", name)
            .executeUpdate();

        // TOCTOU guard: the existence check above and this UPDATE are two statements, not one —
        // a concurrent DELETE between them (same tenant + name) removes the row, and this UPDATE
        // matches nothing. Zero rows means the write never happened, so nothing downstream (cache
        // invalidation, the lifecycle event, the audit row) may run as if it did.
        if (rows == 0) return Response.status(Response.Status.NOT_FOUND).build();

        resolver.invalidate();
        events.emitUpdated(tid, name, null, previousRotatedAt);
        recordAudit(tid, "UPDATE", existing.get().getId().toString(),
            snapshot(name, newDescription, rotatedAt));
        return Response.noContent().build();
    }

    @DELETE @Path("/{name}")
    @Transactional
    @RequiresPermission("lm_admin")
    public Response delete(@PathParam("name") String name) {
        long tid = RequireTenant.current();
        var existing = repo.findByName(tid, name);
        if (existing.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();

        // The row goes, not only its visibility: a row stamped deleted_at kept value_encrypted, which
        // the master key still decrypts. The audit row below records the deletion, never the value.
        int rows = em.createNativeQuery(
            "DELETE FROM platform.secret " +
            "WHERE tenant_id = :tid AND name = :name AND deleted_at IS NULL")
            .setParameter("tid", tid)
            .setParameter("name", name)
            .executeUpdate();

        // Only when the DELETE actually removed something. A concurrent delete between the
        // existence check above and this statement leaves rows == 0: that request already
        // invalidated the cache, and flushing it again for a write that did not happen is an
        // observable side effect of a 404.
        if (rows > 0) {
            resolver.invalidate();
            events.emitDeleted(tid, name, null);
            recordAudit(tid, "DELETE", existing.get().getId().toString(),
                snapshot(name, existing.get().getDescription(), existing.get().getRotatedAt()));
        }
        return rows == 0
            ? Response.status(Response.Status.NOT_FOUND).build()
            : Response.noContent().build();
    }

    /** Only the fields {@code Secret}'s (now-removed) {@code @Auditable} used to declare —
     *  {@code name}/{@code description}/{@code rotatedAt} — never {@code valueEncrypted}
     *  (ciphertext) or the plaintext value, both of which are parameters at the call sites above. */
    private static Map<String, Object> snapshot(String name, String description, Instant rotatedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("rotatedAt", rotatedAt == null ? null : rotatedAt.toString());
        return m;
    }

    /**
     * Records CREATE/UPDATE/DELETE for {@code platform.secret} into {@code core.audit_outbox}.
     * Every write here goes through {@code em.createNativeQuery(...)} to keep the encryption
     * inside PostgreSQL ({@code pgp_sym_encrypt}), which bypasses Hibernate's persistence
     * context — {@code @PostPersist}/{@code @PostUpdate} on {@link io.lumnik.hub.secret.Secret}
     * can never fire on this route, so this is the explicit call site that fills that gap. Same
     * shape as {@code EntityReadResource.recordRead()}: a separate admin-datasource connection,
     * never the caller's own JTA connection, so a broken audit insert cannot poison the secret
     * write it is recording — fail-soft, logged only, never rethrown.
     */
    private void recordAudit(long tenantId, String operation, String entityKey, Map<String, Object> snapshot) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.audit_outbox "
               + "(tenant_id, actor_id, entity_type, entity_key, operation, changes, created_at) "
               + "VALUES (?, ?, 'Secret', ?, ?, cast(? as jsonb), now())")) {
            ps.setLong(1, tenantId);
            Long actorId = TenantContextFilter.currentUser();
            if (actorId != null) ps.setLong(2, actorId); else ps.setNull(2, Types.BIGINT);
            ps.setString(3, entityKey);
            ps.setString(4, operation);
            ps.setString(5, mapper.writeValueAsString(snapshot));
            ps.executeUpdate();
        } catch (Exception e) {
            // Never let audit failure break the secret write — same rule as AuditEntityListener.
            // The throwable goes to the logger, not just its message: this path is silent by
            // design, so this line is the ONLY diagnostic anyone will ever get. A wrapped cause
            // without its stack is an audit that stopped recording for reasons nobody can find.
            LOG.warnf(e, "Audit capture skipped for Secret#%s", operation);
        }
    }
}
