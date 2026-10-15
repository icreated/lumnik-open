package io.lumnik.hub.search.mark;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Code review (post-#671): the deleted {@code SearchProjectionMark} record's compact constructor
 * used to validate a KEY mark's shape (non-blank {@code searchIndex}/{@code entity}/{@code reason},
 * non-blank {@code canonicalKey}) before it ever reached the database — {@code markKeys} itself
 * bound whatever the caller supplied with zero checks, so a bad value would surface only as an
 * opaque {@code SQLException} from deep inside a batch INSERT, via the DB's own {@code
 * search_projection_mark_key_check} constraint. Pins the same validation directly on {@code
 * markKeys}, the one place production code actually writes KEY marks — no dedicated record needed
 * for a check nothing else in the class ever reused.
 */
class SearchProjectionMarkStoreValidationTest {

    private Connection connectionThatMustNeverBeUsed() throws Exception {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        return c;
    }

    @Test
    void blankSearchIndexIsRejectedBeforeAnyStatement() throws Exception {
        Connection c = connectionThatMustNeverBeUsed();

        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(c, 1L, " ", "entity", List.of("k1"), "reason"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("searchIndex");
        verify(c, never()).prepareStatement(anyString());
    }

    @Test
    void blankEntityIsRejectedBeforeAnyStatement() throws Exception {
        Connection c = connectionThatMustNeverBeUsed();

        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "", List.of("k1"), "reason"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entity");
        verify(c, never()).prepareStatement(anyString());
    }

    @Test
    void blankReasonIsRejectedBeforeAnyStatement() throws Exception {
        Connection c = connectionThatMustNeverBeUsed();

        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", List.of("k1"), " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason");
        verify(c, never()).prepareStatement(anyString());
    }

    @Test
    void blankCanonicalKeyInTheBatchIsRejectedBeforeAnyStatement() throws Exception {
        Connection c = connectionThatMustNeverBeUsed();

        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity",
                Arrays.asList("k1", "", "k3"), "reason"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("canonicalKey");
        verify(c, never()).prepareStatement(anyString());
    }

    @Test
    void nullCanonicalKeyInTheBatchIsRejectedBeforeAnyStatement() throws Exception {
        Connection c = connectionThatMustNeverBeUsed();

        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity",
                Arrays.asList("k1", null, "k3"), "reason"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("canonicalKey");
        verify(c, never()).prepareStatement(anyString());
    }

    @Test
    void nullCanonicalKeysListIsRejectedBeforeAnyStatement() throws Exception {
        Connection c = connectionThatMustNeverBeUsed();

        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", null, "reason"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("canonicalKeys");
        verify(c, never()).prepareStatement(anyString());
    }

    /** Copilot review, #671: the no-{@link Connection} public overload (the one most callers use)
     *  called {@code canonicalKeys.isEmpty()} before ever reaching the validated overload above, so a
     *  null list still NPE'd here despite the null-check added for the other entry point. Exercised on
     *  a bare {@code new SearchProjectionMarkStore()} — its unset {@code ds} field must never be
     *  touched, since validation has to reject the null list before any connection is acquired. */
    @Test
    void nullCanonicalKeysListIsRejectedByThePublicNoConnectionOverloadToo() {
        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(1L, "idx", "entity", null, "reason"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("canonicalKeys");
    }

    /** Copilot review, #671: canonical keys encode business values, so the validation failure must
     *  report WHERE in the batch it failed, never echo the offending value itself back into an
     *  exception message that can reach logs or an error surface. */
    @Test
    void blankCanonicalKeyRejectionReportsIndexNotValue() throws Exception {
        Connection c = connectionThatMustNeverBeUsed();

        assertThatThrownBy(() ->
            new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity",
                Arrays.asList("k1", " ", "k3"), "reason"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("index 1")
            .hasMessageNotContaining("got:");
    }

    @Test
    void everyKeyNonBlankStillWritesNormally() throws Exception {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);

        new SearchProjectionMarkStore().markKeys(c, 1L, "idx", "entity", List.of("k1", "k2"), "reason");

        verify(ps).executeUpdate();
    }
}
