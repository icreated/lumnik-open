package io.lumnik.hub.secret;

import io.lumnik.common.tenant.TenantContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PgcryptoSecretResolverIT {

    @Inject SecretResolver resolver;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @Test
    void resolves_existing_secret_with_round_trip_encryption() throws Exception {
        // Seed via runAsSystem (admin DS bypasses RLS)
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted) " +
                    "VALUES (gen_random_uuid(), 1, 0, 'TEST_KEY_RESOLVE', " +
                    "        pgp_sym_encrypt('hello-secret-world', 'master-key-test-ok', 'cipher-algo=aes256'))"
                ).executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) {}
                throw new RuntimeException(e);
            }
        });

        // Resolve under tenant=1 context using TenantContext.callAs
        Optional<String> resolved = TenantContext.callAs(1L, () -> resolver.resolve("TEST_KEY_RESOLVE"));

        assertTrue(resolved.isPresent(), "Secret should be found for tenant 1");
        assertEquals("hello-secret-world", resolved.get());
    }

    /**
     * A name held by nothing must be remembered as such. Every {@code *_env} key resolves through
     * this cache now, and a connector run rebuilds its reader once per CHUNK — so on the deployment
     * the docs recommend (secrets exported into the hub's environment, nothing in the registry) an
     * uncached miss meant one connection acquisition and one zero-row query per chunk, per key,
     * forever. A million-message kafka run is ~2000 chunks and three keys.
     *
     * <p>Caching a miss is only safe because every write flushes: {@code SecretResource} calls
     * {@link PgcryptoSecretResolver#invalidate()} on set, update and delete, so `lm secret set NAME`
     * is visible on the next read rather than five minutes later. This test seeds the row BEHIND
     * that API — straight SQL, no invalidation — which is the one way to observe that the miss was
     * remembered at all.
     */
    @Test
    void a_name_held_by_nothing_is_remembered_as_missing_until_a_write_flushes_it() throws Exception {
        assertTrue(TenantContext.callAs(1L, () -> resolver.resolve("NEG_CACHE_KEY")).isEmpty(),
            "precondition: the name is unknown");

        // The value carries a quote and a backslash on purpose: concatenating it into the INSERT
        // would not compile as SQL, so the binding in the helper is pinned rather than assumed.
        seedSecretBehindTheApi("NEG_CACHE_KEY", "arrived'later\\now");

        assertTrue(TenantContext.callAs(1L, () -> resolver.resolve("NEG_CACHE_KEY")).isEmpty(),
            "the miss was not remembered — this read went back to the database");

        ((PgcryptoSecretResolver) resolver).invalidate();
        assertEquals("arrived'later\\now",
            TenantContext.callAs(1L, () -> resolver.resolve("NEG_CACHE_KEY")).orElseThrow(),
            "a write flushes the cache, so a secret set after a miss is visible at once");
    }

    /**
     * The window a remembered miss opens, and the reason {@link PgcryptoSecretResolver#invalidate()}
     * has to flush twice. {@code SecretResource} calls it from INSIDE its {@code @Transactional}
     * write, before commit — and the resolver reads through the {@code admin} datasource on its own
     * connection, which cannot see an uncommitted row. So a {@code resolve()} landing in that window
     * reads "nothing holds it" and, now that misses are remembered, would answer that for five
     * minutes to a connector whose secret was in fact just written.
     *
     * <p>Before misses were cached the same race healed itself on the next read. It does not any
     * more, so the flush is registered again for after the transaction completes.
     */
    @Test
    void a_miss_read_while_a_write_is_still_uncommitted_does_not_outlive_the_commit() throws Exception {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted) " +
                    "VALUES (gen_random_uuid(), 1, 0, 'RACE_CACHE_KEY', " +
                    "        pgp_sym_encrypt('written-in-this-tx', 'master-key-test-ok', 'cipher-algo=aes256'))"
                ).executeUpdate();

                // What SecretResource does next, still inside the transaction.
                ((PgcryptoSecretResolver) resolver).invalidate();

                // A connector run resolving the same name in that window: the admin datasource is a
                // different connection, so the row is not there yet.
                assertTrue(TenantContext.callAs(1L, () -> resolver.resolve("RACE_CACHE_KEY")).isEmpty(),
                    "precondition: an uncommitted row is invisible to the resolver's own connection");

                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) {}
                throw new RuntimeException(e);
            }
        });

        Optional<String> afterCommit = TenantContext.callAs(1L, () -> resolver.resolve("RACE_CACHE_KEY"));
        assertTrue(afterCommit.isPresent(),
            "the miss cached mid-write outlived the commit — invalidate() must flush again after it");
        assertEquals("written-in-this-tx", afterCommit.get());
    }

    /** Bound, not concatenated: a helper that takes arguments teaches whoever copies it next. */
    private void seedSecretBehindTheApi(String name, String value) {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                em.createNativeQuery("SET LOCAL app.current_tenant = 1").executeUpdate();
                em.createNativeQuery(
                    "INSERT INTO platform.secret(id, tenant_id, org_id, name, value_encrypted) " +
                    "VALUES (gen_random_uuid(), 1, 0, :name, " +
                    "        pgp_sym_encrypt(:value, 'master-key-test-ok', 'cipher-algo=aes256'))")
                    .setParameter("name", name)
                    .setParameter("value", value)
                    .executeUpdate();
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) {}
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void returns_empty_for_missing_name() {
        Optional<String> result = TenantContext.callAs(1L, () -> resolver.resolve("NOT_DEFINED_KEY_XYZ"));
        assertTrue(result.isEmpty(), "Unknown secret should return empty");
    }
}
