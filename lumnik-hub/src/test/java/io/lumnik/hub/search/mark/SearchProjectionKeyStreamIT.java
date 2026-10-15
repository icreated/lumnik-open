package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createMatchSourceTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertRow;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markedKeys;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * #895: a scan with no watermark (the first one after a SearchIndex apply) reads the table's whole history. One query
 * streams its distinct keys through a server-side cursor, and they are marked slice by slice, in the same transaction
 * as the watermark advance.
 *
 * <p>The hook is built outside CDI with targets passed by hand, so no {@code core.search_index} row exists for these
 * tenants and the live reconciler never scans these tables under the test.
 */
@QuarkusTest
class SearchProjectionKeyStreamIT {

    private static final String REASON = "ingestion-run";
    private static final int SLICE = 2;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SearchProjectionWatermarkStore watermarkStore;

    /** A key query as the driver saw it when it ran: the fetch size, and whether the connection was in autocommit. */
    private record KeyQuery(int fetchSize, boolean autoCommit) {}

    @Test
    void oneQueryStreamsTheBacklogToTheMarkerSliceBySlice() throws Exception {
        long tenant = 895_001;
        String table = "t_key_stream_" + System.nanoTime();
        String index = "key-stream-" + System.nanoTime();
        createMatchSourceTable(adminDs, table);
        for (String code : List.of("c1", "c2", "c3", "c4", "c5", "c1")) insertRow(adminDs, table, tenant, code, "n");

        List<KeyQuery> keyQueries = new ArrayList<>();
        List<List<String>> slices = new ArrayList<>();
        SearchProjectionRunHook hook =
            hook(recordingKeyQueries(table, keyQueries), recordingSlices(slices), watermarkStore);

        hook.processTable(tenant, table, List.of(target(table, index)));

        assertThat(keyQueries).as("one cursor over the whole backlog, not one query per page")
            .containsExactly(new KeyQuery(SLICE, false));
        assertThat(slices).as("the marker receives slices, never every key at once")
            .allSatisfy(slice -> assertThat(slice).hasSizeLessThanOrEqualTo(SLICE));
        List<String> expected = codes("c1", "c2", "c3", "c4", "c5");
        assertThat(slices.stream().flatMap(List::stream).toList())
            .as("in key order, so two scans racing on the same keys lock them in the same order")
            .containsExactlyElementsOf(expected);
        assertThat(markedKeys(adminDs, tenant, index, REASON)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table)).isEqualTo(1L);
    }

    /** Marking while the cursor is open must not commit anything early: the slices and the watermark are one unit. */
    @Test
    void theSlicesAlreadyMarkedRollBackWithAFailedAdvance() throws Exception {
        long tenant = 895_002;
        String table = "t_key_stream_rb_" + System.nanoTime();
        String index = "key-stream-rb-" + System.nanoTime();
        createMatchSourceTable(adminDs, table);
        for (String code : List.of("c1", "c2", "c3", "c4", "c5")) insertRow(adminDs, table, tenant, code, "n");
        SearchProjectionWatermarkStore failingAdvance = spy(new SearchProjectionWatermarkStore());
        doThrow(new SQLException("advance failed")).when(failingAdvance).advance(any(), anyLong(), anyString(), any());
        List<List<String>> slices = new ArrayList<>();
        SearchProjectionRunHook hook = hook(adminDs, recordingSlices(slices), failingAdvance);

        assertThatThrownBy(() -> hook.processTable(tenant, table, List.of(target(table, index))))
            .hasMessage("advance failed");

        assertThat(slices).as("every slice was written before the advance failed").hasSize(3);
        assertThat(markCount(adminDs, tenant, index, REASON)).as("no slice survives the rollback").isZero();
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + table)).isZero();
    }

    private SearchProjectionRunHook hook(AgroalDataSource ds, SearchProjectionMarkStore marks,
                                         SearchProjectionWatermarkStore watermarks) {
        SearchProjectionRunHook hook = new SearchProjectionRunHook();
        hook.ds = ds;
        hook.markStore = marks;
        hook.watermarkStore = watermarks;
        hook.matchKeysBatchSize = SLICE;
        return hook;
    }

    /** The match entity's own source target: {@code src_code} is its one match column. */
    private static SearchProjectionRunHook.SourceTarget target(String table, String index) {
        return new SearchProjectionRunHook.SourceTarget("KeyStream" + table, List.of("src_code"), List.of(index));
    }

    /** The real mark store, recording each slice it is handed. */
    private static SearchProjectionMarkStore recordingSlices(List<List<String>> slices) {
        return new SearchProjectionMarkStore() {
            @Override
            public void markKeysAsAdmin(Connection c, long tenantId, String searchIndex, String entity,
                                        List<String> canonicalKeys, String reason) throws SQLException {
                slices.add(List.copyOf(canonicalKeys));
                super.markKeysAsAdmin(c, tenantId, searchIndex, entity, canonicalKeys, reason);
            }
        };
    }

    private static List<String> codes(String... codes) {
        return List.of(codes).stream().map(code -> MatchKeyEncoding.encode(List.of(code))).toList();
    }

    /** {@code adminDs}, recording every {@code SELECT DISTINCT} over {@code table} as it executes. */
    private AgroalDataSource recordingKeyQueries(String table, List<KeyQuery> keyQueries) {
        return proxy(AgroalDataSource.class, (ds, method, args) -> {
            Object result = invoke(method, adminDs, args);
            if (!(result instanceof Connection c)) return result;
            return proxy(Connection.class, (conn, cm, cargs) -> {
                Object stmt = invoke(cm, c, cargs);
                if (!(stmt instanceof PreparedStatement ps) || !cm.getName().equals("prepareStatement")
                        || !((String) cargs[0]).startsWith("SELECT DISTINCT") || !((String) cargs[0]).contains(table)) {
                    return stmt;
                }
                return proxy(PreparedStatement.class, (s, sm, sargs) -> {
                    if (sm.getName().equals("executeQuery")) keyQueries.add(new KeyQuery(ps.getFetchSize(), c.getAutoCommit()));
                    return invoke(sm, ps, sargs);
                });
            });
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(SearchProjectionKeyStreamIT.class.getClassLoader(),
            new Class<?>[] {type}, handler));
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
