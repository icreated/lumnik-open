package io.lumnik.hub.tenant;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the user_id deactivation guard actually FIRES on an RLS-enforced hub — the posture the
 * unit tests cannot see (they mock the repository). SET LOCAL app.current_tenant is
 * transaction-scoped: a lookup that runs outside a transaction loses the GUC, RLS hides every
 * row, and the guard silently passes a SCIM-archived user through. This IT pins the guard to the
 * production posture: an archived user's row must be visible to the check, and the check must
 * say "deactivated".
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class UserDeactivationGuardRlsIT {

  private static final long TENANT = 4243L;
  private static final long ARCHIVED_USER = 424301L;
  private static final long ACTIVE_USER = 424302L;

  @Inject @DataSource("admin") AgroalDataSource adminDs; // owner — bypasses RLS for seeding
  @Inject UserLoginGuard guard;

  private void seed() {
    // Seed as the OWNER (RLS does not apply), same idiom as RlsHarnessSmokeIT.
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO identity.tenant (id, code, name, created_at, updated_at) "
          + "VALUES (" + TENANT + ", 'rls-guard-probe', 'rls guard probe', now(), now()) "
          + "ON CONFLICT DO NOTHING");
      st.execute("INSERT INTO identity.app_user (id, tenant_id, login, name, created_at, updated_at, archived_at) "
          + "VALUES (" + ARCHIVED_USER + ", " + TENANT + ", 'rls-guard-archived', 'Archived Probe', now(), now(), now()) "
          + "ON CONFLICT DO NOTHING");
      st.execute("INSERT INTO identity.app_user (id, tenant_id, login, name, created_at, updated_at) "
          + "VALUES (" + ACTIVE_USER + ", " + TENANT + ", 'rls-guard-active', 'Active Probe', now(), now()) "
          + "ON CONFLICT DO NOTHING");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @AfterEach
  void clear() {
    CurrentTenantHolder.clear();
  }

  @Test
  void archived_user_is_seen_as_deactivated_under_enforced_rls() {
    seed();
    // Called exactly as the request filter calls it: no surrounding transaction (the guard
    // bean must open its own, or the SET LOCAL tenant GUC evaporates and RLS hides the row).
    CurrentTenantHolder.set(TENANT);
    assertThat(guard.isDeactivated(ARCHIVED_USER))
        .as("the deactivation guard must see the archived row on an RLS-enforced hub "
            + "(an invisible row means the guard never fires in production)")
        .isTrue();
  }

  @Test
  void active_user_is_not_flagged_under_enforced_rls() {
    seed();
    CurrentTenantHolder.set(TENANT);
    assertThat(guard.isDeactivated(ACTIVE_USER)).isFalse();
  }

  @Test
  void unknown_or_foreign_tenant_user_passes_through() {
    seed();
    // Another tenant's token id (RLS hides the row) and a nonexistent id both mean
    // "no visible row" — the guard must pass them through, not deny or explode.
    CurrentTenantHolder.set(TENANT);
    assertThat(guard.isDeactivated(999_999_999L)).isFalse();
  }
}
