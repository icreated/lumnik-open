package io.lumnik.hub.tenant;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fresh install must carry the default tenant — id 1, the tenant the shipped realm's
 * users claim (tenant_id=1). Without it, the very first external login walks the JIT
 * provisioning path and dies on fk_user_tenant (found live by the mobile spike,
 * 2026-07-11: selfhost never seeded identity.tenant; lm-cli tokens masked it because
 * their user_id claim short-circuits JIT).
 */
@QuarkusTest
class DefaultTenantSeedIT {

  @Inject AgroalDataSource ds;

  @Test
  void fresh_install_carries_the_default_tenant() {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT code, archived_at, deleted_at FROM identity.tenant WHERE id = 1")) {
      assertThat(rs.next())
          .as("tenant 1 must exist on a fresh install — the shipped realm claims tenant_id=1")
          .isTrue();
      assertThat(rs.getString("code")).isEqualTo("default");
      assertThat(rs.getTimestamp("archived_at")).as("seeded tenant is live").isNull();
      assertThat(rs.getTimestamp("deleted_at")).as("seeded tenant is live").isNull();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void seed_does_not_bite_the_tenant_id_sequence() {
    // The contract: the sequence must never hand out the seeded id 1 — that lower bound is
    // all the seed relies on (today V1 starts the sequence at 1000, but that exact value is
    // an implementation detail, deliberately not pinned here). Read start_value from the
    // catalog — nextval() would consume a value in the DB this test shares with the suite.
    try (Connection c = ds.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
             "SELECT start_value FROM pg_sequences "
                 + "WHERE schemaname = 'identity' AND sequencename = 'tenant_id_seq'")) {
      assertThat(rs.next()).as("identity.tenant_id_seq must exist").isTrue();
      assertThat(rs.getLong("start_value"))
          .as("the sequence must never hand out the seeded id")
          .isGreaterThan(1L);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
