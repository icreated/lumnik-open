package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Registry case: the 5 s statement timeout used to surface as a bare 500
 * ("ViewDataReader.read: …") instead of a spoken refusal — a view must never
 * fail without saying why in métier language.
 */
class ViewDataReaderFailureMappingTest {

    @Test void queryCanceled_57014_isASpokenRefusal() {
        RuntimeException ex = ViewDataReader.mapSqlFailure(new SQLException("canceling statement", "57014"));
        assertInstanceOf(IllegalArgumentException.class, ex,
            "timeout must ride the honest-refusal channel (400), not a 500");
        assertTrue(ex.getMessage().contains("5 s") && ex.getMessage().contains("filter"),
            () -> "the refusal must name the budget and the remedy, got: " + ex.getMessage());
    }

    @Test void dateShapedGarbage_22007_keepsItsRefusal() {
        RuntimeException ex = ViewDataReader.mapSqlFailure(new SQLException("bad date", "22007"));
        assertInstanceOf(IllegalArgumentException.class, ex);
        assertTrue(ex.getMessage().contains("date"));
    }

    @Test void anythingElse_staysAGenericFailure() {
        RuntimeException ex = ViewDataReader.mapSqlFailure(new SQLException("boom", "08006"));
        assertFalse(ex instanceof IllegalArgumentException);
        assertTrue(ex.getMessage().contains("boom"));
    }
}
