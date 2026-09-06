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
 * The default-scope read under the production posture — enforced RLS, no surrounding transaction —
 * the scar {@link UserDeactivationGuardRlsIT} explains. Without the bean's own transaction, "no
 * default" would be the silent answer for every user.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class UserDefaultScopeSourceRlsIT {

  private static final long TENANT = 4244L;
  private static final long WITH_DEFAULT = 424401L;
  private static final long WITHOUT_DEFAULT = 424402L;

  @Inject @DataSource("admin") AgroalDataSource adminDs; // owner — bypasses RLS for seeding
  @Inject UserDefaultScopeSource source;

  private void seed() {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("INSERT INTO identity.tenant (id, code, name, created_at, updated_at) "
          + "VALUES (" + TENANT + ", 'rls-scope-probe', 'rls scope probe', now(), now()) "
          + "ON CONFLICT DO NOTHING");
      st.execute("INSERT INTO identity.app_user (id, tenant_id, login, name, default_scope, created_at, updated_at) "
          + "VALUES (" + WITH_DEFAULT + ", " + TENANT + ", 'rls-scope-compta', 'Compta Probe', 'compta', now(), now()) "
          + "ON CONFLICT DO NOTHING");
      st.execute("INSERT INTO identity.app_user (id, tenant_id, login, name, created_at, updated_at) "
          + "VALUES (" + WITHOUT_DEFAULT + ", " + TENANT + ", 'rls-scope-none', 'No Default Probe', now(), now()) "
          + "ON CONFLICT DO NOTHING");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @AfterEach
  void clear() {
    CurrentTenantHolder.clear();
    TenantContextFilter.CURRENT_USER.remove();
  }

  @Test
  void the_users_default_is_read_under_enforced_rls() {
    seed();
    CurrentTenantHolder.set(TENANT);
    TenantContextFilter.CURRENT_USER.set(WITH_DEFAULT);
    assertThat(source.forCurrentUser())
        .as("the read must see the row on an RLS-enforced hub — an invisible row means "
            + "every user's default is silently 'none' in production")
        .contains("compta");
  }

  @Test
  void a_user_with_no_default_reads_as_empty() {
    seed();
    CurrentTenantHolder.set(TENANT);
    TenantContextFilter.CURRENT_USER.set(WITHOUT_DEFAULT);
    assertThat(source.forCurrentUser()).isEmpty();
  }

  @Test
  void no_user_in_context_and_a_foreign_tenants_user_both_read_as_empty() {
    seed();
    CurrentTenantHolder.set(TENANT);
    assertThat(source.forCurrentUser()).as("no user bound").isEmpty();
    CurrentTenantHolder.set(TENANT + 1);
    TenantContextFilter.CURRENT_USER.set(WITH_DEFAULT);
    assertThat(source.forCurrentUser()).as("foreign tenant's row is invisible").isEmpty();
  }
}
