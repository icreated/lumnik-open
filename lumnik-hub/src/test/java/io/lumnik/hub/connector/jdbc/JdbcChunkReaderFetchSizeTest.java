package io.lumnik.hub.connector.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.jdbc.postgresql.PostgresDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the fetch size of the chunk SELECT with a mocked {@link Connection}/{@link PreparedStatement} — no
 * database. Nothing a real read returns tells the two apart: without a fetch size the driver still hands
 * back every row of the chunk, only in batches of its own choosing (see the reader). The fetch size must be
 * one row more than the SELECT's row limit — the chunk plus the row it peeks at (#1196) — and be set before
 * {@code executeQuery()}, which runs the first fetch.
 */
class JdbcChunkReaderFetchSizeTest {

  @Test
  void the_fetch_size_is_one_row_past_the_select_limit_set_before_the_query_runs() throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    HikariDataSource ds = mock(HikariDataSource.class);
    HikariPoolRegistry registry = mock(HikariPoolRegistry.class);
    when(registry.acquire(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(ds);
    when(ds.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.getMetaData()).thenReturn(mock(ResultSetMetaData.class)); // no column, no row: only the wiring matters

    new JdbcChunkReader("jdbc:postgresql://source/erp", "u", "p", null, "public", "t", null, List.of("id"),
        JdbcChunkReader.Mode.FULL, new PostgresDialect(), registry, ZoneOffset.UTC)
        .read(500, ConnectorCursor.empty(), null);

    var order = inOrder(ps);
    order.verify(ps).setFetchSize(502);
    order.verify(ps).executeQuery();
  }
}
