package io.lumnik.hub.search.mark;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): a failed mark write or watermark read says its label and the SQLSTATE, as every Jdbc
 * statement does; the driver's words stay in the cause.
 */
class SearchMarkJdbcFailureTest {

    @Test
    void a_failed_key_mark_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: duplicate key value violates unique constraint", "23505");

        assertThatThrownBy(() -> new SearchProjectionMarkStore()
                .markKeys(refusing(driver), 1L, "orders_idx", "order", List.of("k1"), "test"))
            .hasMessage("SearchProjectionMarkStore.markKeys failed (SQLSTATE 23505)")
            .hasCause(driver);
    }

    @Test
    void a_failed_watermark_read_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: canceling statement due to statement timeout", "57014");

        assertThatThrownBy(() -> new SearchProjectionWatermarkStore().watermark(refusing(driver), 1L, "connector.t_a"))
            .hasMessage("SearchProjectionWatermarkStore.watermark failed (SQLSTATE 57014)")
            .hasCause(driver);
    }

    private static Connection refusing(SQLException driver) throws SQLException {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(driver);
        return c;
    }
}
