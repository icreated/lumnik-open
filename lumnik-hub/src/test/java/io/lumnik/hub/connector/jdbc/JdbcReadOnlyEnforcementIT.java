package io.lumnik.hub.connector.jdbc;

import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that HikariPoolRegistry always opens read-only connections.
 *
 * <p>HikariConfig.setReadOnly(true) is set unconditionally. Attempting a write via such a
 * connection must throw a SQLException — JDBC drivers and servers enforce this differently,
 * so the assertion is loose: any SQLException is acceptable.
 */
@Testcontainers
class JdbcReadOnlyEnforcementIT {

  private static final PostgresDialect PG_DIALECT = new PostgresDialect();

  @Container
  static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("s").withUsername("u").withPassword("p");

  @BeforeAll
  static void seed() throws Exception {
    try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "u", "p");
         var st = c.createStatement()) {
      st.execute("CREATE TABLE t (id INT)");
    }
  }

  @Test
  void connection_acquired_via_registry_is_read_only() throws Exception {
    HikariPoolRegistry reg = new HikariPoolRegistry();
    DataSource ds = reg.acquire(PG.getJdbcUrl(), "u", "p", 1L, PG_DIALECT);

    try (Connection c = ds.getConnection()) {
      assertTrue(c.isReadOnly(), "Connection must report isReadOnly() = true");
    }

    reg.evictAll();
  }

  @Test
  void read_only_transaction_rejects_writes() throws Exception {
    HikariPoolRegistry reg = new HikariPoolRegistry();
    DataSource ds = reg.acquire(PG.getJdbcUrl(), "u", "p", 1L, PG_DIALECT);

    try (Connection c = ds.getConnection()) {
      // Disable autocommit to start an explicit transaction.
      // PostgreSQL enforces read-only at the transaction level: SET TRANSACTION READ ONLY
      // is sent by the PGJDBC driver when autoCommit=false and Connection.isReadOnly()=true.
      c.setAutoCommit(false);

      assertTrue(c.isReadOnly(), "Connection must report isReadOnly() = true");

      try (var st = c.createStatement()) {
        SQLException ex = assertThrows(SQLException.class,
            () -> st.execute("INSERT INTO t VALUES (1)"),
            "DML in a read-only transaction must throw SQLException");

        assertNotNull(ex.getMessage(), "SQLException must have a message");
        // PostgreSQL error: "cannot execute INSERT in a read-only transaction"
        // SQLState 25006 = READ_ONLY_SQL_TRANSACTION
        assertTrue(
            (ex.getSQLState() != null && ex.getSQLState().startsWith("25")) ||
            ex.getMessage().toLowerCase().contains("read-only") ||
            ex.getMessage().toLowerCase().contains("read only"),
            "Expected read-only error but got SQLState=" + ex.getSQLState() +
            " message=" + ex.getMessage());
      }

      c.rollback();
    }

    reg.evictAll();
  }
}
