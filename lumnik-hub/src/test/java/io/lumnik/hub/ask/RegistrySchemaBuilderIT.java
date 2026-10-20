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
        UUID connId    = UUID.fromString("00000000-0000-0000-0000-0000000081bb");
        exec("DELETE FROM connector.connector WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = " + tenant);
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, "
           + "sync_mode, skip_policy) "
           + "VALUES ('" + connId + "', " + tenant + ", 'src--c_order', 'jdbc-generic', '{}'::jsonb, "
           + "true, ARRAY['atelierA'], "
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
    void aTableAnotherScopesConnectorAlsoWritesIsLeftOutOfTheCard() {
        // #1186: each connector writing one table registers it; the table holds every writer's rows
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000081cc");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, "
           + "sync_mode, skip_policy) "
           + "VALUES ('" + other + "', 8100, 'src2--c_order', 'jdbc-generic', '{}'::jsonb, "
           + "true, ARRAY['atelierB'], "
           + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
           + "VALUES (gen_random_uuid(), 8100, 'jdbc:c_order', 'connector', 't_c_order', "
           + "'{\"documentno\":\"text\"}'::jsonb, '" + other + "')");

        assertThat(builder.build(8100L, "atelierA").tables()).isEmpty();
        assertThat(builder.build(8100L, "atelierB").tables()).isEmpty();
    }

    @Test
    void aWriterRegisteredAgainstAnotherTenantsConnectorClosesTheCard() {
        // #1200 review: the scope decision never reads another tenant's tags, whatever the registry says
        UUID foreign = UUID.fromString("00000000-0000-0000-0000-0000000081ee");
        exec("DELETE FROM connector.connector WHERE id = '" + foreign + "'");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, "
           + "sync_mode, skip_policy) "
           + "VALUES ('" + foreign + "', 8101, 'src--foreign', 'jdbc-generic', '{}'::jsonb, "
           + "true, ARRAY['atelierA'], "
           + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
           + "VALUES (gen_random_uuid(), 8100, 'jdbc:c_order', 'connector', 't_c_order', '{}'::jsonb, '" + foreign + "')");

        assertThat(builder.build(8100L, "atelierA").tables()).isEmpty();
    }

    @Test
    void aTableAWriterWithNoConnectorAlsoRegistersIsLeftOutOfTheCard() {
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
           + "VALUES (gen_random_uuid(), 8100, 'internal:c_order', 'connector', 't_c_order', '{}'::jsonb)");

        assertThat(builder.build(8100L, "atelierA").tables()).isEmpty();
    }

    @Test
    void unknownScopeYieldsEmptyCard() {
        SchemaCard card = builder.build(8100L, "nope");
        assertThat(card.tables()).isEmpty();
    }

    /**
     * #521 — jsonb canonicalises object keys by length then bytes, so a naive read of
     * {@code schema_inferred} hands back {@code city, code, name, segment, credit_limit}
     * for the very header (#496's own bench) that was inferred and physically created as
     * {@code code, name, city, segment, credit_limit}. The card must follow the PHYSICAL
     * table's column order ({@code information_schema.columns.ordinal_position}), which is
     * the order {@code \d} and {@code SELECT *} already show, not jsonb's canonical order.
     */
    @Test
    void cardColumnsFollowThePhysicalTableOrderNotJsonbsCanonicalOrder() {
        long tenant = 8100L;
        UUID connId    = UUID.fromString("00000000-0000-0000-0000-0000000081dd");
        exec("DROP TABLE IF EXISTS connector.t_c_customer");
        exec("CREATE TABLE connector.t_c_customer "
           + "(code text, name text, city text, segment text, credit_limit numeric(18,4))");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled, tags, "
           + "sync_mode, skip_policy) "
           + "VALUES ('" + connId + "', " + tenant + ", 'src--c_customer', 'jdbc-generic', '{}'::jsonb, "
           + "true, ARRAY['atelierA'], "
           + "'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
           + "VALUES (gen_random_uuid(), " + tenant + ", 'jdbc:c_customer', 'connector', 't_c_customer', "
           + "'{\"code\":\"text\",\"name\":\"text\",\"city\":\"text\",\"segment\":\"text\",\"credit_limit\":\"numeric(18,4)\"}'::jsonb, '"
           + connId + "')");

        SchemaCard card = builder.build(tenant, "atelierA");

        SchemaCard.Table t = card.tables().stream()
            .filter(tbl -> tbl.name().equals("connector.t_c_customer")).findFirst().orElseThrow();
        assertThat(t.columns()).extracting(SchemaCard.Column::name)
            .containsExactly("code", "name", "city", "segment", "credit_limit");
    }
}
