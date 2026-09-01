package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.webhook.SecretCipher;
import io.lumnik.extraction.spi.ask.DiscoveryResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DiscoveryServiceIT {

    @Inject DiscoveryService service;
    @Inject SecretCipher cipher;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    // In %test mode, admin DS runs as lumnik_app (quoted "admin" key is not injectable
    // via @ConfigProperty with unquoted name — same workaround as AdLabelProviderIT).
    private static final String USER = "lumnik_app";
    private static final String PASSWORD = "lumnik_app_dev_password";
    String url; // resolved in seed() via metadata — admin url is not injectable under DevServices

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = adminDs.getConnection()) { url = c.getMetaData().getURL(); }

        long tenant = 8200L;
        UUID mappingId = UUID.fromString("00000000-0000-0000-0000-0000000082aa");
        exec("DELETE FROM connector.connector WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.connector_mapping WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.connector_credential WHERE tenant_id = " + tenant);
        exec("DELETE FROM connector.schema_card WHERE tenant_id = " + tenant);

        byte[] ct = cipher.encrypt(USER + ":" + PASSWORD);
        UUID credId = UUID.fromString("00000000-0000-0000-0000-0000000082cc");
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO connector.connector_credential (id, tenant_id, name, kind, ciphertext, iv) "
              + "VALUES (?, ?, 'src--credential', 'Basic', ?, ?)")) {
            ps.setObject(1, credId); ps.setLong(2, tenant); ps.setBytes(3, ct); ps.setBytes(4, new byte[0]);
            ps.executeUpdate();
        }

        exec("INSERT INTO connector.connector_mapping (id, tenant_id, name, target_entity, spec_format, spec_content) "
           + "VALUES ('" + mappingId + "', " + tenant + ", 'm_order', 'public.c_order', 'yaml', 'x')");
        UUID connId = UUID.fromString("00000000-0000-0000-0000-0000000082bb");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, credential_id, mapping_id, "
           + "enabled, tags, sync_mode, skip_policy) VALUES ('" + connId + "', " + tenant
           + ", 'src--c_order', 'jdbc-generic', '{\"url\":\"" + url + "\",\"user\":\"" + USER + "\"}'::jsonb, '"
           + credId + "', '" + mappingId + "', true, ARRAY['atelierB'], 'Incremental', "
           + "'{\"type\":\"SkipUpTo\",\"maxSkips\":100}'::jsonb)");
        exec("INSERT INTO connector.dynamic_table (id, tenant_id, mapping_id, table_schema, table_name, schema_inferred, connector_id) "
           + "VALUES (gen_random_uuid(), " + tenant + ", 'jdbc:c_order', 'connector', 't_c_order', "
           + "'{\"documentno\":\"text\",\"custcol_47\":\"text\"}'::jsonb, '" + connId + "')");

        exec("DROP TABLE IF EXISTS ad_column; DROP TABLE IF EXISTS ad_table; DROP TABLE IF EXISTS ad_element");
        exec("CREATE TABLE ad_table (ad_table_id int primary key, tablename text)");
        exec("CREATE TABLE ad_element (ad_element_id int primary key, name text)");
        exec("CREATE TABLE ad_column (ad_column_id int primary key, ad_table_id int, columnname text, name text, ad_element_id int)");
        exec("INSERT INTO ad_table VALUES (100, 'C_Order')");
        exec("INSERT INTO ad_element VALUES (9, 'N° de chantier')");
        exec("INSERT INTO ad_column VALUES (1, 100, 'DocumentNo', 'Document No', NULL)");
        exec("INSERT INTO ad_column VALUES (2, 100, 'CustCol_47', 'raw', 9)");
    }

    @Test
    void rediscoverBuildsEnrichedCardAndPersists() {
        DiscoveryResult r = service.rediscover(8200L, "atelierB");
        assertThat(r.scope()).isEqualTo("atelierB");
        assertThat(r.tableCount()).isEqualTo(1);
        assertThat(r.columnCount()).isEqualTo(2);
        assertThat(r.enriched()).isTrue();
        assertThat(r.sourceType()).isEqualTo("jdbc-ad");

        Optional<SchemaCard> loaded = service.load(8200L, "atelierB");
        assertThat(loaded).isPresent();
        SchemaCard.Table t = loaded.get().tables().get(0);
        assertThat(t.columns()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("custcol_47");
            assertThat(c.label()).isEqualTo("N° de chantier");
        });
    }
}
