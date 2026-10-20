package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.extraction.spi.CitationLocator;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1205 review: a database failure behind the row door is a 500 that names what failed, never the
 * driver's message — Postgres quotes the table, the column and the value it choked on.
 */
class DataRowResourceErrorTest {

  @AfterEach
  void clearTenant() {
    CurrentTenantHolder.clear();
  }

  @Test
  @SuppressWarnings("unchecked")
  void a_database_failure_answers_500_without_the_drivers_message() throws Exception {
    CurrentTenantHolder.set(1L);
    DataRowResource door = new DataRowResource();
    CitationLocator locator = mock(CitationLocator.class);
    when(locator.mappingIdForRowHash("h")).thenReturn(Optional.of("jdbc:customers"));
    door.citations = mock(Instance.class);
    when(door.citations.isUnsatisfied()).thenReturn(false);
    when(door.citations.get()).thenReturn(locator);
    door.adminDs = mock(AgroalDataSource.class);
    when(door.adminDs.getConnection())
        .thenThrow(new SQLException("value \"P-1008\" violates check on connector.t_customers"));

    Response r = door.get("h");

    assertThat(r.getStatus()).isEqualTo(500);
    assertThat(String.valueOf(r.getEntity())).doesNotContain("P-1008").doesNotContain("t_customers");
  }

  @Test
  @SuppressWarnings("unchecked")
  void a_failure_that_is_not_sql_answers_the_same_500() throws Exception {
    // #1206 review: narrowing the catch to let the scope 403 through let every other failure
    // escape to the default error page instead
    CurrentTenantHolder.set(1L);
    DataRowResource door = new DataRowResource();
    CitationLocator locator = mock(CitationLocator.class);
    when(locator.mappingIdForRowHash("h")).thenReturn(Optional.of("jdbc:customers"));
    door.citations = mock(Instance.class);
    when(door.citations.isUnsatisfied()).thenReturn(false);
    when(door.citations.get()).thenReturn(locator);
    door.adminDs = mock(AgroalDataSource.class);
    when(door.adminDs.getConnection()).thenThrow(new IllegalStateException("pool closed for t_customers"));

    Response r = door.get("h");

    assertThat(r.getStatus()).isEqualTo(500);
    assertThat(String.valueOf(r.getEntity())).contains("could not read the cited row").doesNotContain("t_customers");
  }

  @Test
  void the_logged_failure_names_its_kind_and_sqlstate_not_the_drivers_message() {
    // #1205 review, round 2: the log is no place for a row's values either
    String logged = DataRowResource.failureSummary(
        new SQLException("value \"P-1008\" violates check on connector.t_customers", "23514"));

    assertThat(logged).contains("SQLException").contains("23514").doesNotContain("P-1008");
  }
}
