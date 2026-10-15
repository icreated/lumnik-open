package io.lumnik.hub.search.mark;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkStore.MAX_KEYS_PER_INSERT;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@code markKeys}' chunking directly, with a mocked {@link Connection}/{@link
 * PreparedStatement} — no database, no {@code @QuarkusTest}. A Copilot review on PR #709 flagged
 * the previous unbounded single INSERT: a large ingestion scan or reconciliation pass can easily
 * find tens of thousands of distinct keys, and PostgreSQL refuses more than 65535 bind parameters
 * in one statement (6 per key here -&gt; ~10,922 keys). {@link SearchProjectionMarkStore#markKeys(
 * Connection, long, String, String, List, String)} now splits into {@link
 * SearchProjectionMarkStore#MAX_KEYS_PER_INSERT}-sized chunks, each its own statement — this test
 * proves the split actually happens at the right boundary (against the real constant, not a
 * duplicated literal that could silently drift from it), not just that a small call still works.
 */
class SearchProjectionMarkStoreBatchingTest {

    private static final int MAX = MAX_KEYS_PER_INSERT;

    private static List<String> keys(int count) {
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) keys.add("key-" + i);
        return keys;
    }

    @Test
    void exactlyMaxKeysIsOneStatement() throws Exception {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);

        new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", keys(MAX), "reason");

        verify(c, times(1)).prepareStatement(anyString());
        verify(ps, times(1)).executeUpdate();
    }

    @Test
    void oneMoreThanMaxSplitsIntoTwoStatements() throws Exception {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);

        new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", keys(MAX + 1), "reason");

        verify(c, times(2)).prepareStatement(anyString());
        verify(ps, times(2)).executeUpdate();
    }

    @Test
    void aFewThousandKeysSplitsIntoTheExpectedNumberOfChunks() throws Exception {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);

        int total = MAX * 3 + 7; // 3 full chunks + 1 partial
        new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", keys(total), "reason");

        verify(c, times(4)).prepareStatement(anyString());
        verify(ps, times(4)).executeUpdate();
    }

    @Test
    void emptyKeysNeverPreparesAStatement() throws Exception {
        Connection c = mock(Connection.class);

        new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", List.of(), "reason");

        verify(c, times(0)).prepareStatement(anyString());
    }

    @Test
    void fewerThanMaxKeysIsStillOneStatement() throws Exception {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);

        new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", keys(3), "reason");

        verify(c, times(1)).prepareStatement(anyString());
    }
}
