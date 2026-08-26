package io.lumnik.hub.audit;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.secret.Secret;
import io.lumnik.hub.secret.SecretRepository;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.lumnik.identity.user.User;
import io.lumnik.identity.user.UserRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof that the globally-registered {@link AuditEntityListener} (wired via
 * {@code META-INF/orm.xml}) actually fires on a real, annotated production entity through the
 * real Panache persist/update path — not through direct listener calls on fixture POJOs, which is
 * as far as {@code AuditEntityListenerTest} (Task 1) could go. That test says explicitly: nothing
 * yet proves the orm.xml-registered listener fires on a real Hibernate-mapped entity. This class
 * is that proof, for {@link User} ({@code Long} id, identity module).
 * <p>
 * {@link Secret} ({@code UUID} id, hub module) is deliberately NOT covered here as a "does the
 * listener fire" case anymore. It was, until a whole-branch review (F1) found that {@code
 * Secret} carried {@code @Auditable} while every production write to it goes through {@code
 * SecretResource}'s native SQL — which bypasses Hibernate's persistence context, so the listener
 * could never fire on the route production actually takes. The two tests that used to live here
 * persisted a {@code Secret} through Hibernate directly and passed — proving the listener works,
 * on a route nobody uses. {@code @Auditable} has been removed from {@link Secret} for that
 * reason (see its javadoc); real Secret coverage is now the explicit {@code recordAudit()} call
 * sites in {@code SecretResource}, pinned in {@code SecretResourceAuditTest}. The test below pins
 * the new, honest state of this class: a direct Hibernate write to Secret produces no row.
 */
@QuarkusTest
class AuditWiringTest {

    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject SecretRepository secrets;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private Long tenantId;

    @BeforeEach
    void setUp() throws Exception {
        // identity.tenant carries no tenant_id column itself (it IS the tenant table) — no RLS
        // session variable needed to create it.
        utx.begin();
        Tenant t = new Tenant("AUD-" + System.nanoTime(), "Audit Wiring Test Tenant");
        tenants.persist(t);
        utx.commit();
        tenantId = t.getId();

        TenantContextFilter.CURRENT_TENANT.set(tenantId);
        CurrentTenantHolder.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextFilter.CURRENT_TENANT.remove();
        TenantContextFilter.CURRENT_USER.remove();
        CurrentTenantHolder.clear();
    }

    @Test
    void updating_a_users_locked_flag_writes_an_audit_row_with_the_declared_fields() throws Exception {
        TenantContextFilter.CURRENT_USER.set(701L);

        Long userId = inTx(() -> {
            User u = new User(tenantId, "jdoe-" + System.nanoTime(), "Jane Doe");
            u.setEmail("jane@example.com");
            u.setDefaultScope("compta");
            users.persist(u);
            return u.getId();
        });

        // The real business write: load through the Panache repository, mutate, let the
        // transaction commit flush the UPDATE — exactly how a real request touches this entity.
        inTx(() -> {
            User u = users.findById(userId);
            u.setLocked(true);
            return null;
        });

        AuditOutboxRow row = findRow("User", String.valueOf(userId), "UPDATE");
        assertNotNull(row, "expected a real @PostUpdate-driven audit row for User — the orm.xml "
                + "listener must fire on the actual Panache update path, not just on a fixture");
        assertEquals(701L, row.getActorId(), "the row must name the actor who made the change");
        assertEquals(Set.of("login", "email", "locked", "defaultScope"), row.getChanges().keySet());
        assertEquals(Boolean.TRUE, row.getChanges().get("locked"));
        assertEquals("jane@example.com", row.getChanges().get("email"));
    }

    /**
     * F1 aftermath. {@link Secret} carries no {@code @Auditable} (removed — see its javadoc): a
     * direct Hibernate write to it, exactly like the one this test performs, must NOT produce an
     * audit row. If this ever starts failing, either the annotation crept back without the
     * production write path changing to match (still a lie), or something re-registered a
     * listener on Secret by another route — either way it needs a human, not a silent pass.
     */
    @Test
    void hibernate_persisting_a_secret_directly_produces_no_row_without_auditable() throws Exception {
        UUID secretId = inTx(() -> {
            Secret s = new Secret();
            s.setTenantId(tenantId);
            s.setName("NOANN-" + System.nanoTime());
            s.setValueEncrypted("irrelevant-ciphertext-bytes".getBytes());
            secrets.persist(s);
            return s.getId();
        });

        AuditOutboxRow row = findRow("Secret", secretId.toString(), "CREATE");
        assertNull(row, "Secret carries no @Auditable — a direct Hibernate write must not "
                + "silently start recording; real Secret coverage is SecretResource's explicit "
                + "recordAudit() call sites (SecretResourceAuditTest), not this route");
    }

    private AuditOutboxRow findRow(String entityType, String entityKey, String operation) {
        List<AuditOutboxRow> results = em.createQuery(
                "select r from AuditOutboxRow r where r.entityType = :type and r.entityKey = :key "
                + "and r.operation = :op order by r.id desc",
                AuditOutboxRow.class)
            .setParameter("type", entityType)
            .setParameter("key", entityKey)
            .setParameter("op", operation)
            .setMaxResults(1)
            .getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    private <T> T inTx(Callable<T> work) throws Exception {
        utx.begin();
        try {
            em.createNativeQuery("SET LOCAL app.current_tenant = " + tenantId).executeUpdate();
            T result = work.call();
            utx.commit();
            return result;
        } catch (Exception e) {
            try { utx.rollback(); } catch (Exception ignored) { /* best-effort */ }
            throw e;
        }
    }
}
