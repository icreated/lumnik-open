package io.lumnik.hub.api.data;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.CurrentTenantHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** #1649: a failed registry read names the read and its SQLSTATE, never the driver's message. */
class DataTablesJdbcFailureTest {

  private static final SQLException DENIED =
      new SQLException("permission denied for table connector.t_customers", "42501");

  @AfterEach
  void clearTenant() {
    CurrentTenantHolder.clear();
  }

  private static Connection refusing() throws SQLException {
    Connection c = mock(Connection.class);
    when(c.prepareStatement(anyString())).thenThrow(DENIED);
    return c;
  }

  @Test
  void the_readable_table_lookup_names_itself() throws Exception {
    Connection c = refusing();
    assertThatThrownBy(() -> DynamicTableScope.readableTable(c, "jdbc:customers", 1L))
        .hasMessage("DynamicTableScope.readableTable failed (SQLSTATE 42501)")
        .hasCause(DENIED);
  }

  @Test
  void the_list_names_its_registry_read() throws Exception {
    CurrentTenantHolder.set(1L);
    DataTablesResource door = new DataTablesResource();
    Connection c = refusing();
    door.ds = mock(AgroalDataSource.class);
    when(door.ds.getConnection()).thenReturn(c);
    assertThatThrownBy(door::list)
        .hasMessage("DataTablesResource.list failed (SQLSTATE 42501)")
        .hasCause(DENIED);
  }
}
