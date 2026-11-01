package io.lumnik.hub.workflow;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViewDocumentsTest {

    private static final WorkflowGraph GRAPH = WorkflowGraph.from(new WorkflowManifestParser().parse("""
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: wf-reset }
            spec:
              scope: s
              provenance: declared
              on: { table: connector.v_x, column: st, key: k, order: at }
              dormancy: { after: 1m }
              states:
                - { code: A, initial: true }
                - { code: B, terminal: true }
              transitions:
                - { from: [A], to: B }
            """));

    private static ViewDocuments.Row row(String raw, boolean unreadable, String identity) {
        return new ViewDocuments.Row(raw, unreadable, List.of(identity));   // the identity only tells the rows apart
    }

    @Test
    void one_top_row_is_current() {
        var d = new ViewDocuments.Document("D1", List.of(row("OPEN", false, "a")));
        assertThat(ViewDocuments.decide(d, ViewDocuments.Row::raw)).isEqualTo(new ViewDocuments.Current(d.top().get(0)));
    }

    @Test
    void a_tie_that_agrees_picks_the_first_identity() {
        var d = new ViewDocuments.Document("D1", List.of(row("OPEN", false, "a"), row("OPEN", false, "b")));
        assertThat(ViewDocuments.decide(d, ViewDocuments.Row::raw)).isEqualTo(new ViewDocuments.Current(d.top().get(0)));
    }

    @Test
    void a_tie_that_disagrees_is_ambiguous() {
        var d = new ViewDocuments.Document("D1", List.of(row("OPEN", false, "a"), row("CLOSED", false, "b")));
        assertThat(ViewDocuments.decide(d, ViewDocuments.Row::raw)).isEqualTo(new ViewDocuments.Ambiguous());
    }

    @Test
    void an_unreadable_key_is_unreadable_before_anything_else() {
        var d = new ViewDocuments.Document("D1", List.of(row("OPEN", true, "a"), row("CLOSED", true, "b")));
        assertThat(ViewDocuments.decide(d, ViewDocuments.Row::raw)).isEqualTo(new ViewDocuments.Unreadable());
    }

    /**
     * The cursor is declared and committed; if the connection then cannot go back to autocommit, a transaction of its
     * own may be open — and reconciling under it would hold it for the whole sweep. The walk stops before any FETCH.
     */
    @Test
    void a_failed_reset_after_the_commit_stops_before_the_first_fetch() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet none = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c);
        tracksAutocommit(c);
        when(c.createStatement()).thenReturn(st);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(none);
        doThrow(new SQLException("reset refused")).when(c).setAutoCommit(true);

        assertThatThrownBy(() -> ViewDocuments.forEachPage(ds, 1L, GRAPH, OrderColumn.TEXT, false, List.of(), 10,
                page -> { }))
            .hasRootCauseMessage("reset refused");   // the message says the SQLSTATE, never the driver (#1648)
        verify(c).commit();
        verify(st, never()).executeQuery(anyString());
    }

    /**
     * #1649: back to autocommit after a rollback that failed, pgjdbc would COMMIT what the cursor's transaction held —
     * and so would the pool's reset. The session is ended instead.
     */
    @Test
    void a_failed_rollback_ends_the_session_instead_of_committing() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        Connection physical = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        tracksAutocommit(c);
        when(c.unwrap(Connection.class)).thenReturn(physical);
        SQLException boom = new SQLException("boom");
        when(c.createStatement()).thenThrow(boom);   // the tenant's SET LOCAL, the first statement
        SQLException rollback = new SQLException("connection gone");
        doThrow(rollback).when(c).rollback();

        assertThatThrownBy(() -> ViewDocuments.forEachPage(ds, 1L, GRAPH, OrderColumn.TEXT, false, List.of(), 10,
                page -> { }))
            .hasCause(boom);
        assertThat(boom.getSuppressed()).containsExactly(rollback);
        verify(c, never()).setAutoCommit(true);
        verify(physical).close();
    }

    /** getAutoCommit answers what setAutoCommit last set, starting as a pool hands it out: in autocommit. */
    private static void tracksAutocommit(Connection c) throws SQLException {
        AtomicBoolean auto = new AtomicBoolean(true);
        when(c.getAutoCommit()).thenAnswer(inv -> auto.get());
        doAnswer(inv -> {
            auto.set(inv.getArgument(0));
            return null;
        }).when(c).setAutoCommit(anyBoolean());
    }
}
