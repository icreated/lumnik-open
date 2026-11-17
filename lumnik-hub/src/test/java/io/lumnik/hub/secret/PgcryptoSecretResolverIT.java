package io.lumnik.hub.secret;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.lumnik.hub.testsupport.TestSecrets;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PgcryptoSecretResolverIT {

    @Inject SecretResolver resolver;
    @Inject EntityManager em;
    @Inject UserTransaction utx;
    @Inject TestSecrets testSecrets;
    @Inject @DataSource("admin") AgroalDataSource admin;
    @ConfigProperty(name = "lumnik.secret.master-key") String masterKey;

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
        Optional<String> resolved = resolveAsScopeless("TEST_KEY_RESOLVE");

        assertTrue(resolved.isPresent(), "Secret should be found for tenant 1");
        assertEquals("hello-secret-world", resolved.get());
    }

    /**
     * #2020: a miss is not remembered. A secret set after a read found nothing is read at the next one, on every
     * replica: here it is set behind the API, which is how another replica's write looks from this one. The value
     * carries a quote and a backslash on purpose: concatenated into the INSERT it would not compile as SQL.
     */
    @Test
    void a_secret_set_on_another_replica_after_a_miss_is_read_at_the_next_read() throws Exception {
        assertTrue(resolveAsScopeless("NEG_CACHE_KEY").isEmpty(), "precondition: the name is unknown");

        seedSecretBehindTheApi("NEG_CACHE_KEY", "arrived'later\\now");

        assertEquals("arrived'later\\now", resolveAsScopeless("NEG_CACHE_KEY").orElseThrow());
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
        Optional<String> result = resolveAsScopeless("NOT_DEFINED_KEY_XYZ");
        assertTrue(result.isEmpty(), "Unknown secret should return empty");
    }

    /** A read as a scopeless connector's: what every test above means by "the resolver answers". */
    private Optional<String> resolveAsScopeless(String name) {
        return resolveAs(List.of(), name);
    }

    /** Fail-closed, as with the tenant: a door that forgot to say who reads must not read as the scopeless case. */
    @Test
    void a_read_with_no_audience_is_a_mis_wiring() {
        assertThrows(IllegalStateException.class,
            () -> TenantContext.callAs(1L, () -> resolver.resolve("NOT_DEFINED_KEY_XYZ")));
    }

    /**
     * The refusal names the reader's own scopes and the gesture, never the secret's: a bound caller probing its own
     * connector learns nothing of the scopes it does not hold (here {@code rh}), nor which of its own the secret carries.
     */
    @Test
    void a_refusal_names_the_readers_scopes_and_not_the_secrets() {
        String name = "SCOPED_REFUSAL_" + Math.abs(System.nanoTime());
        testSecrets.put(1L, name, "v", List.of("achats", "rh"));

        var refused = assertThrows(SecretNotServedException.class, () -> resolveAs(List.of("ventes", "achats"), name));

        assertEquals("secret " + name + " does not serve scope achats, ventes: lm secret scope " + name
            + " SCOPES replaces the scopes it serves", refused.getMessage());
    }

    /**
     * A secret narrowed on another replica (behind the API, as seen from here) is refused here at the next read:
     * whether it serves the reader is asked on every read, whatever this replica kept.
     */
    @Test
    void a_scope_narrowed_on_another_replica_is_refused_on_the_next_read() {
        String name = "SCOPED_ELSEWHERE_" + Math.abs(System.nanoTime());
        testSecrets.put(1L, name, "v", List.of("ventes"));
        assertEquals(Optional.of("v"), resolveAs(List.of("ventes"), name));

        Jdbc.update(admin, "narrow behind the API",
            "UPDATE platform.secret SET scopes = '{achats}' WHERE tenant_id = 1 AND name = ?", ps -> ps.setString(1, name));

        assertThrows(SecretNotServedException.class, () -> resolveAs(List.of("ventes"), name));
    }

    /**
     * #2020: a value rotated on another replica (behind the API, as seen from here) reaches every reader at its next
     * read, scopeless or scoped: the row's ciphertext changed.
     */
    @Test
    void a_value_rotated_on_another_replica_reaches_every_reader_at_its_next_read() {
        String open = "ROTATED_OPEN_" + Math.abs(System.nanoTime());
        String scoped = "ROTATED_SCOPED_" + Math.abs(System.nanoTime());
        testSecrets.put(1L, open, "v1");
        testSecrets.put(1L, scoped, "v1", List.of("ventes"));
        assertEquals(Optional.of("v1"), resolveAs(List.of(), open));
        assertEquals(Optional.of("v1"), resolveAs(List.of("ventes"), scoped));

        rotateBehindTheApi(open, "v2");
        rotateBehindTheApi(scoped, "v2");

        assertEquals(Optional.of("v2"), resolveAs(List.of(), open));
        assertEquals(Optional.of("v2"), resolveAs(List.of("ventes"), scoped));
    }

    /** #2020: a secret deleted on another replica is gone at the next read, for a reader that had it. */
    @Test
    void a_secret_deleted_on_another_replica_is_gone_at_the_next_read() {
        String name = "DELETED_ELSEWHERE_" + Math.abs(System.nanoTime());
        testSecrets.put(1L, name, "v");
        assertEquals(Optional.of("v"), resolveAs(List.of(), name));

        Jdbc.update(admin, "delete behind the API", "DELETE FROM platform.secret WHERE tenant_id = 1 AND name = ?",
            ps -> ps.setString(1, name));

        assertEquals(Optional.empty(), resolveAs(List.of(), name));
    }

    /**
     * #2020: while the ciphertext holds, a reader reuses what was decrypted, scoped or not. Shown by handing the
     * resolver a wrong master key: a read that decrypted again would fail.
     */
    @Test
    void a_reader_reuses_its_value_while_the_ciphertext_holds() {
        PgcryptoSecretResolver live = ClientProxy.unwrap((PgcryptoSecretResolver) resolver);
        for (List<String> audience : List.of(List.<String>of(), List.of("ventes"))) {
            String name = "KEPT_" + audience.size() + "_" + Math.abs(System.nanoTime());
            testSecrets.put(1L, name, "v1", audience);
            assertEquals(Optional.of("v1"), resolveAs(audience, name));

            String key = live.masterKey;
            live.masterKey = "not-the-master-key";
            try {
                assertEquals(Optional.of("v1"), resolveAs(audience, name), "audience " + audience);
            } finally {
                live.masterKey = key;
            }
        }
    }

    /** #2020: two readers of one secret, with different scopes, are two reads in the trail. */
    @Test
    void each_readers_read_is_recorded() {
        String name = "AUDITED_TWICE_" + Math.abs(System.nanoTime());
        testSecrets.put(1L, name, "v", List.of("ventes"));
        TenantContextFilter.CURRENT_TENANT.set(1L);
        try {
            assertEquals(Optional.of("v"), resolveAs(List.of("ventes"), name));
            assertEquals(Optional.of("v"), resolveAs(List.of("achats", "ventes"), name));
        } finally {
            TenantContextFilter.CURRENT_TENANT.remove();
        }
        assertEquals(2L, readEvents(name));
    }

    /**
     * A reader is audited once per value it reads and per {@code KEPT_TTL}, scoped or not: a run reads under
     * TenantScope, whose tenant the outbox records under, and one event per secret per chunk would drown the trail. A
     * value rotated on any replica is a new one: its first read is recorded. A read the outbox did not record (no
     * tenant to record it under) leaves no mark: the next read that can be recorded is.
     */
    @Test
    void a_reader_is_audited_once_per_value_and_only_when_recorded() {
        for (List<String> audience : List.of(List.<String>of(), List.of("ventes"))) {
            String name = "AUDITED_" + audience.size() + "_" + Math.abs(System.nanoTime());
            testSecrets.put(1L, name, "v1", audience);

            assertEquals(Optional.of("v1"), resolveAs(audience, name));   // nothing to record it under
            assertEquals(0L, readEvents(name));

            TenantContextFilter.CURRENT_TENANT.set(1L);
            try {
                for (int i = 0; i < 3; i++) assertEquals(Optional.of("v1"), resolveAs(audience, name));
                assertEquals(1L, readEvents(name), "audience " + audience);
                rotateBehindTheApi(name, "v2");
                assertEquals(Optional.of("v2"), resolveAs(audience, name));
            } finally {
                TenantContextFilter.CURRENT_TENANT.remove();
            }
            assertEquals(2L, readEvents(name), "audience " + audience);
        }
    }

    private void rotateBehindTheApi(String name, String value) {
        Jdbc.update(admin, "rotate behind the API",
            "UPDATE platform.secret SET value_encrypted = pgp_sym_encrypt(?, ?, 'cipher-algo=aes256') "
                + "WHERE tenant_id = 1 AND name = ?", ps -> {
                ps.setString(1, value);
                ps.setString(2, masterKey);
                ps.setString(3, name);
            });
    }

    private long readEvents(String name) {
        return Jdbc.queryOne(admin, "read events",
            "SELECT count(*) FROM core.event_outbox WHERE event_type = 'platform.secret.read' AND payload->>'name' = ?",
            ps -> ps.setString(1, name), rs -> rs.getLong(1)).orElseThrow();
    }

    private Optional<String> resolveAs(List<String> audience, String name) {
        return TenantContext.callAs(1L, () -> SecretAudience.get(audience, () -> resolver.resolve(name)));
    }
}
