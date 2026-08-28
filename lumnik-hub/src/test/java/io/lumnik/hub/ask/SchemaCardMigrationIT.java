package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SchemaCardMigrationIT {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Test
    void schemaCardTableExists() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM information_schema.tables " +
                "WHERE table_schema='connector' AND table_name='schema_card'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
