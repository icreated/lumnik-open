package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.connector.api.writers.BulkItemWriter;
import io.lumnik.hub.connector.api.writers.CopyItemWriter;
import io.lumnik.hub.connector.api.writers.JpaItemWriter;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WriterTiersTest {
  @Test
  void jpa_writer_profile_is_safe() {
    var w = new JpaItemWriter<String>() {
      @Override
      protected Object toEntity(String item, ConnectorContext ctx) {
        return item;
      }
    };
    assertThat(w.profile()).isInstanceOf(WritePerformanceProfile.Safe.class);
  }

  @Test
  void bulk_writer_profile_is_bulk() {
    var w = new BulkItemWriter<String>() {
      @Override
      protected String insertSql() {
        return "INSERT INTO test (col) VALUES (?)";
      }

      @Override
      protected void bindRow(PreparedStatement ps, String item) {
      }
    };
    assertThat(w.profile()).isInstanceOf(WritePerformanceProfile.Bulk.class);
  }

  @Test
  void copy_writer_profile_is_bulkload() {
    var w = new CopyItemWriter<String>() {
      @Override
      protected String copyCommand() {
        return "COPY test (col) FROM STDIN";
      }

      @Override
      protected String toCsvRow(String item) {
        return item;
      }
    };
    assertThat(w.profile()).isInstanceOf(WritePerformanceProfile.BulkLoad.class);
  }

  /** The driver's own text for a unique violation: it quotes the stored value (#1679). */
  private static final SQLException QUOTES_A_VALUE =
      new SQLException("Batch entry 3 INSERT INTO t VALUES ('4111-1111') was aborted: Key (card)=(4111-1111) already exists", "23505");

  private static DataSource failingWith(SQLException failure) throws SQLException {
    DataSource ds = mock(DataSource.class);
    Connection conn = mock(Connection.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenThrow(failure);
    when(conn.unwrap(org.mockito.ArgumentMatchers.any(Class.class))).thenThrow(failure);
    return ds;
  }

  @Test
  void bulk_writer_failure_names_the_sqlstate_not_the_row() throws Exception {
    DataSource ds = failingWith(QUOTES_A_VALUE);
    var w = new BulkItemWriter<String>() {
      @Override protected DataSource dataSource() { return ds; }
      @Override protected String insertSql() { return "INSERT INTO t (card) VALUES (?)"; }
      @Override protected void bindRow(PreparedStatement ps, String item) { }
    };
    var out = w.write(List.of("4111-1111"), null);
    assertThat(out).isInstanceOfSatisfying(WriteOutcome.AllFailed.class,
        f -> assertThat(f.reason()).contains("23505").doesNotContain("4111-1111"));
  }

  @Test
  void copy_writer_failure_names_the_sqlstate_not_the_row() throws Exception {
    DataSource ds = failingWith(QUOTES_A_VALUE);
    var w = new CopyItemWriter<String>() {
      @Override protected DataSource dataSource() { return ds; }
      @Override protected String copyCommand() { return "COPY t (card) FROM STDIN"; }
      @Override protected String toCsvRow(String item) { return item; }
    };
    var out = w.write(List.of("4111-1111"), null);
    assertThat(out).isInstanceOfSatisfying(WriteOutcome.AllFailed.class,
        f -> assertThat(f.reason()).contains("23505").doesNotContain("4111-1111"));
  }
}
