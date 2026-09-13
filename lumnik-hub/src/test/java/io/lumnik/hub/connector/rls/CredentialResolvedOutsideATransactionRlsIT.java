package io.lumnik.hub.connector.rls;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.types.ResolvedCredential;
import io.lumnik.hub.connector.exceptions.CredentialNotFoundException;
import io.lumnik.hub.webhook.SecretCipher;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A stored credential resolves with NO ambient transaction — on the harness that can tell.
 *
 * <p>{@code connector.connector_credential} is RLS-enforced (policy {@code tenant_isolation}, V9,
 * amended by V22 for the system sentinel) and the Agroal interceptor that issues
 * {@code SET LOCAL app.current_tenant} only fires for a JTA-enlisted connection. So a caller with
 * no transaction read a table it could not see: no row, and a not-found for a credential that
 * exists. Every caller happened to hold one until {@code ConnectorType.runStart} deliberately did
 * not — it probes the source and must not keep a pooled connection while it does — and the
 * consequence was quiet: the resolver threw, the engine degraded to an UNBOUNDED run with a WARN,
 * and a jdbc connector using a stored credential went back to following the inserts. The ceiling
 * off for exactly the way a deployment is wired.
 *
 * <p><b>This profile is the only place that is visible.</b> Two earlier attempts to anchor it under
 * the default profile passed with the defect in place: the first wrapped the call in a transaction
 * the engine never gives it, and the second — correct in shape — still passed, because the default
 * test profile does not enforce RLS at all, so a read with no tenant GUC succeeds regardless. A
 * green test in that profile said nothing about either version of the code. See
 * {@code RlsHarnessSmokeIT} for what this harness guarantees.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class CredentialResolvedOutsideATransactionRlsIT {

    private static final long TENANT = 4243L;

    @Inject CredentialResolver resolver;
    @Inject SecretCipher cipher;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;   // the owner, so the fixture can seed past RLS

    /** Seeds a Basic credential as the owner and returns its id. */
    private UUID seedCredential(String secret) {
        try (Connection c = adminDs.getConnection()) {
            // Seeded by raw SQL as the OWNER, which bypasses RLS — the fixture has to be able to
            // plant a row the app role may or may not be allowed to see, which is the whole
            // question. The ciphertext goes through the app's own cipher so the resolver can
            // actually decrypt it.
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM connector.connector_credential WHERE tenant_id = " + TENANT);
            }
            String sql = "INSERT INTO connector.connector_credential"
                + " (id, tenant_id, name, kind, ciphertext, iv, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'Basic', ?, ?, now(), now())";
            UUID id = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, id);
                ps.setLong(2, TENANT);
                ps.setString(3, "rls-cred-" + System.nanoTime());
                ps.setBytes(4, cipher.encrypt(secret));
                ps.setBytes(5, new byte[0]);
                ps.executeUpdate();
            }
            return id;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void aCredentialResolvesWithNoAmbientTransaction() {
        UUID id = seedCredential("alice:s3cr3t");

        // No transaction, exactly as ConnectorType.runStart is called. Tenant context only.
        ResolvedCredential r = TenantContext.callAs(TENANT, () -> resolver.resolve(id));

        assertThat(r).isInstanceOf(ResolvedCredential.Basic.class);
        assertThat(((ResolvedCredential.Basic) r).password()).isEqualTo("s3cr3t");
    }

    /**
     * The control, and a security check in the same breath: another tenant must NOT resolve this
     * credential. It proves RLS is genuinely enforced through the resolver's own transaction — so
     * the test above is not green merely because nothing was being isolated — and it proves the
     * resolver did not take the easy road of forcing system mode inside itself, which would have
     * handed one tenant another's secret.
     */
    @Test
    void anotherTenantStillCannotResolveIt() {
        UUID id = seedCredential("alice:s3cr3t");

        assertThatThrownBy(() -> TenantContext.callAs(1L, () -> resolver.resolve(id)))
            .as("the transaction the resolver opens carries the AMBIENT tenant, not a wider one")
            .isInstanceOf(CredentialNotFoundException.class);
    }
}
