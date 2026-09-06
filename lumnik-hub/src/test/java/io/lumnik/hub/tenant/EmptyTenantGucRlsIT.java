package io.lumnik.hub.tenant;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An empty tenant GUC must mean "no rows", never an exception — on every table under RLS.
 *
 * <p>{@code core/V21__rls_tenant_nullif_guard.sql} exists because it did not: the live hub set
 * {@code app.current_tenant} to the empty string during an async connector run, {@code ''::bigint}
 * raised 22P02, and the query that blew up was swallowed into a silent no-op. V21 wrapped every
 * cast in {@code NULLIF(…, '')} and says so — "every tenant RLS policy", across "all three
 * schemas".
 *
 * <p>identity is the fourth schema. It has its own Flyway history, so V21 could not reach it
 * however it was written, and its six policies kept the raw cast. The gap was visible from
 * outside: with the tenant GUC empty, {@code core.notification_rule} answered {@code []} while
 * {@code identity.app_user} answered HTTP 500. {@code V18__rls_tenant_nullif_guard.sql} closes it.
 *
 * <p>This test is anchored on the DATABASE rather than on a list of schemas, so the next policy
 * written without the guard fails here whichever schema it lands in — which is the check that was
 * missing when V21 was written.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EmptyTenantGucRlsIT {

  /** PostgreSQL "invalid input syntax" — what {@code ''::bigint} raises. */
  private static final String INVALID_TEXT_REPRESENTATION = "22P02";

  @Inject AgroalDataSource defaultDs;

  @Test
  void every_rls_table_degrades_to_no_rows_when_the_tenant_guc_is_empty() throws SQLException {
    List<String> tables = rlsTables();
    assertThat(tables)
        .as("the enforced harness must actually have RLS tables — a sweep over nothing is not a "
            + "green, it is a blind spot")
        .isNotEmpty();

    List<String> throwers = new ArrayList<>();
    for (String table : tables) {
      // A fresh connection per table: a failed statement aborts its transaction, and every
      // later query in it would then fail for the wrong reason.
      try (Connection c = defaultDs.getConnection()) {
        c.setAutoCommit(false);                     // SET LOCAL needs a transaction to survive
        try (Statement st = c.createStatement()) {
          st.execute("SET LOCAL app.current_tenant = ''");
          st.executeQuery("SELECT count(*) FROM " + table).close();
        } catch (SQLException e) {
          // Only the cast failure is this test's business. A missing GRANT is a different
          // question and must not be reported as this one.
          if (INVALID_TEXT_REPRESENTATION.equals(e.getSQLState())) {
            throwers.add(table + " — " + e.getMessage().strip());
          }
        }
        c.rollback();
      }
    }

    assertThat(throwers)
        .as("an empty tenant GUC must degrade to no rows: wrap the policy's cast in "
            + "NULLIF(current_setting('app.current_tenant', true), '')::bigint")
        .isEmpty();
  }

  private List<String> rlsTables() throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = defaultDs.getConnection();
         Statement st = c.createStatement();
         var rs = st.executeQuery(
             "SELECT n.nspname || '.' || c.relname FROM pg_class c "
             + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relrowsecurity "
             + "ORDER BY 1")) {
      while (rs.next()) out.add(rs.getString(1));
    }
    return out;
  }
}
