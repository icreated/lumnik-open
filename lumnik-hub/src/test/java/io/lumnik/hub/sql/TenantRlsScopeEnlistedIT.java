package io.lumnik.hub.sql;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The %test profile pins {@code readonly.jdbc.transactions=DISABLED}, so every other test
 * exercises {@link TenantRlsScope} on a NON-enlisted connection — a posture production never
 * has. {@link ReadonlyTransactionsProfile} re-enables JTA enlistment for the readonly datasource, which is exactly
 * how the four production callers (view render/detail, workflow data-truth, ask execution)
 * reach it.
 *
 * <p>Pinned here because Agroal (Quarkus 3.33) refuses {@code setReadOnly} on an enlisted
 * connection — a refusal every monorepo gate missed and the open-edition OTK publish proof
 * caught as {@code view render → 500}. The assertion goes both ways: applyTo must survive
 * enlistment, AND the tenant binding must actually be present afterwards (a silently
 * discarded {@code SET LOCAL} is the cross-tenant failure this class exists to prevent).
 */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)
class TenantRlsScopeEnlistedIT {

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonly;

    @Test
    void applyTo_survives_a_jta_enlisted_connection_and_the_binding_holds() {
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = readonly.getConnection()) {
                TenantRlsScope.applyTo(c, 424242L);
                try (var st = c.createStatement();
                     var rs = st.executeQuery("SELECT current_setting('app.current_tenant', true)")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1))
                        .as("SET LOCAL must ride the enlisted transaction, not be discarded")
                        .isEqualTo("424242");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
