package io.lumnik.hub.testsupport;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Puts a secret in a tenant's {@code lm secret} registry, as {@code lm secret set} would: the only place a manifest's
 * {@code *_env} reference resolves since #2000. Tests used to hand a connector its secret as a JVM property, which
 * production no longer reads.
 */
@ApplicationScoped
public class TestSecrets {

    @Inject @DataSource("admin") AgroalDataSource admin;

    @ConfigProperty(name = "lumnik.secret.master-key") String masterKey;

    /** Holds {@code value} under {@code name} for {@code tenantId}, replacing an earlier one; visible on the next read (#2020). */
    public void put(long tenantId, String name, String value) {
        put(tenantId, name, value, List.of());
    }

    /** {@link #put(long, String, String)}, for the connectors carrying every one of {@code scopes} (#2015). */
    public void put(long tenantId, String name, String value, List<String> scopes) {
        Jdbc.update(admin, "TestSecrets.put", "DELETE FROM platform.secret WHERE tenant_id = ? AND name = ?", ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, name);
        });
        Jdbc.update(admin, "TestSecrets.put",
            "INSERT INTO platform.secret (id, tenant_id, name, value_encrypted, scopes) "
                + "VALUES (gen_random_uuid(), ?, ?, pgp_sym_encrypt(?, ?, 'cipher-algo=aes256'), ?)", ps -> {
                ps.setLong(1, tenantId);
                ps.setString(2, name);
                ps.setString(3, value);
                ps.setString(4, masterKey);
                ps.setArray(5, ps.getConnection().createArrayOf("text", scopes.toArray()));
            });
    }
}
