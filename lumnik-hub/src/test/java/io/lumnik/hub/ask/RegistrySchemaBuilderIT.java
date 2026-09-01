package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class RegistrySchemaBuilderIT {

    @Inject RegistrySchemaBuilder builder;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        long tenant = 8100L;
        UUID mappingId = UUID.fromString("00000000-0000-0000-0000-0000000081aa");
        UUID connId    = UUID.fromString("00000000-0000-0000-0000-0000000081bb");
        exec("DELETE FROM connector.connector WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.connector_mapping WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = " + tenant);
        exec("INSERT INTO connector.connector_mapping (id, tenant_id, name, target_entity, spec_format, spec_content) "
           + "VALUES ('" + mappingId + "', " + tenant + ", 'm_order', 'public.c_order', 'yaml', 'x')");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, mapping_id, enabled, tags, "
           + "sync_mode, skip_policy) "
           + "VALUES ('" + connId + "', " + tenant + ", 'src--c_order', 'jdbc-generic', '{}'::jsonb, '"
           + mappingId + "', true, ARRAY['atelierA'], "
           + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        // Discovery now links hub tables to their connector via dynamic_table.connector_id.
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
           + "VALUES (gen_random_uuid(), " + tenant + ", 'jdbc:c_order', 'connector', 't_c_order', "
           + "'{\"documentno\":\"text\",\"grandtotal\":\"numeric(18,4)\"}'::jsonb, '" + connId + "')");
    }

    @Test
    void buildsCardFromRegistryForScope() {
        SchemaCard card = builder.build(8100L, "atelierA");
        assertThat(card.scope()).isEqualTo("atelierA");
        assertThat(card.tables()).hasSize(1);
        SchemaCard.Table t = card.tables().get(0);
        assertThat(t.name()).isEqualTo("connector.t_c_order");
        assertThat(t.columns()).extracting(SchemaCard.Column::name)
            .containsExactlyInAnyOrder("documentno", "grandtotal");
        assertThat(t.columns()).allSatisfy(c -> assertThat(c.label()).isEqualTo(c.name()));
    }

    @Test
    void unknownScopeYieldsEmptyCard() {
        SchemaCard card = builder.build(8100L, "nope");
        assertThat(card.tables()).isEmpty();
    }
}
