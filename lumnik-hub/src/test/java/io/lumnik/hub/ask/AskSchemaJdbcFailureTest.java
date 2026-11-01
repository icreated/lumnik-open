package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.Test;

import io.lumnik.hub.entity.EntityManifestParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649 (#1648): a failed schema read says its own label and the SQLSTATE, as every Jdbc statement does; the
 * driver's words stay in the cause.
 */
class AskSchemaJdbcFailureTest {

    private static final SQLException DRIVER =
        new SQLException("ERROR: permission denied for table connector", "42501");

    private static AgroalDataSource refusing() throws SQLException {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(DRIVER);
        AgroalDataSource ds = mock(AgroalDataSource.class);
        when(ds.getConnection()).thenReturn(c);
        return ds;
    }

    @Test
    void a_failed_registry_read_says_its_label_and_the_sqlstate_only() throws Exception {
        RegistrySchemaBuilder builder = new RegistrySchemaBuilder();
        builder.adminDs = refusing();

        assertThatThrownBy(() -> builder.resolve(1L, "sales"))
            .hasMessage("RegistrySchemaBuilder.resolve(connectors) failed (SQLSTATE 42501)")
            .hasCause(DRIVER);
    }

    @Test
    void a_failed_entity_read_says_its_label_and_the_sqlstate_only() throws Exception {
        EntitySchemaBuilder builder = new EntitySchemaBuilder();
        builder.adminDs = refusing();

        assertThatThrownBy(() -> builder.resolve("sales"))
            .hasMessage("EntitySchemaBuilder.resolve failed (SQLSTATE 42501)")
            .hasCause(DRIVER);
    }

    @Test
    void a_stored_manifest_that_no_longer_parses_is_named() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("name")).thenReturn("customer");
        when(rs.getString("yaml_text")).thenReturn("kind: Entity");
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        AgroalDataSource ds = mock(AgroalDataSource.class);
        when(ds.getConnection()).thenReturn(c);
        IllegalArgumentException invalid = new IllegalArgumentException("sources: required");
        EntityManifestParser parser = mock(EntityManifestParser.class);
        when(parser.parse(any())).thenThrow(invalid);

        EntitySchemaBuilder builder = new EntitySchemaBuilder();
        builder.adminDs = ds;
        builder.parser = parser;

        assertThatThrownBy(() -> builder.resolve("sales"))
            .hasMessage("EntitySchemaBuilder.resolve: stored manifest customer does not parse")
            .hasCause(invalid);
    }
}
