package io.lumnik.hub.entity;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): a failed catalog read or definition lock says its label and the SQLSTATE, as every Jdbc
 * statement does; the driver's words stay in the cause.
 */
class EntityJdbcFailureTest {

    @Test
    void a_failed_view_column_read_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: canceling statement due to statement timeout", "57014");
        Connection c = refusing(driver);

        assertThatThrownBy(() -> EntityViewCompiler.viewColumnTypes(c, "customer"))
            .hasMessage("EntityViewCompiler.viewColumnTypes failed (SQLSTATE 57014)")
            .hasCause(driver);
    }

    @Test
    void a_failed_definition_lock_says_its_label_and_the_sqlstate_only() throws Exception {
        SQLException driver = new SQLException("ERROR: canceling statement due to lock timeout", "55P03");
        Connection c = refusing(driver);

        assertThatThrownBy(() -> EntityDefinitionLock.exclusive(c, "customer"))
            .hasMessage("EntityDefinitionLock.exclusive failed (SQLSTATE 55P03)")
            .hasCause(driver);
    }

    private static Connection refusing(SQLException driver) throws SQLException {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(driver);
        return c;
    }
}
