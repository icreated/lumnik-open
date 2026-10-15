package io.lumnik.hub.export;

import io.lumnik.hub.entity.EntityManifest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the JDBC cursor contract directly, with a mocked {@link Connection}/{@link PreparedStatement}
 * — no database, no {@code @QuarkusTest}. {@link EntitySnapshotStreamerStreamingIT}'s bounded-memory
 * test only observes writes to the CSV sink, which come from {@code CSVPrinter}/{@code Writer}
 * buffering, not from pgJDBC's fetch behavior: rows are always written to the sink one at a time as
 * {@code ResultSetRowIterator} walks them, whether pgJDBC fetched them via a server-side cursor or
 * materialized the whole {@code ResultSet} client-side in one round trip. So a regression that drops
 * {@code setFetchSize(FETCH_SIZE)} — silently falling back to pgJDBC's default of loading the entire
 * result set into client memory — would leave every write-pattern assertion in that IT unchanged.
 * This test instead pins the one thing that actually distinguishes bounded-cursor reads from
 * full materialization: {@code setFetchSize(FETCH_SIZE)} must be called on the
 * {@code PreparedStatement}, and it must happen before {@code executeQuery()} — pgJDBC only honors
 * a fetch size set before the query executes.
 */
class EntitySnapshotStreamerFetchSizeTest {

    private final EntitySnapshotStreamer streamer = new EntitySnapshotStreamer();

    @Test
    void setsFetchSizeBeforeExecutingTheQuery() throws Exception {
        EntityManifest entity = new EntityManifest("Entity", "FetchSizeEntity677", List.of("code"),
            List.of(new EntityManifest.Source("t_fetchsize677_erp", Map.of("cust_id", "code"))));

        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // empty result: only the header/wiring matters here

        streamer.stream(connection, entity, SnapshotShape.canonical(List.of("code")), List.of("code"), ExportManifest.Format.DEFAULT,
            new ByteArrayOutputStream());

        var order = inOrder(ps);
        order.verify(ps).setFetchSize(EntitySnapshotStreamer.FETCH_SIZE);
        order.verify(ps).executeQuery();
    }
}
