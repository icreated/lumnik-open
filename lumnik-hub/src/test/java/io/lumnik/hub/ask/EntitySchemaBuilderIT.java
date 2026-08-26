package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manifest's per-column {@code label:} must flow into the SchemaCard column label — the
 * enrichment that lets a kind:View render "Code client" instead of the raw canonical name. A
 * column with no label falls back to the canonical name (the prior behavior).
 */
@QuarkusTest
class EntitySchemaBuilderIT {

    @Inject EntitySchemaBuilder builder;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    private static final String MANIFEST = """
        kind: Entity
        name: LabelTest
        match: [customer_code]
        scopes: [labelscope]
        columns:
          customer_code:
            label: "Code client"
        sources:
          - table: t_lbl_src
            map: { code: customer_code, nom: name }
        """;

    @BeforeEach
    void seed() {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM connector.entity_manifest WHERE name = 'labeltest'");
        } catch (Exception e) { throw new RuntimeException(e); }
        String sql = "INSERT INTO connector.entity_manifest (name, yaml_text, scopes) "
                   + "VALUES ('labeltest', ?, ARRAY['labelscope']::text[])";
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, MANIFEST);
            ps.executeUpdate();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void manifestLabelFlowsIntoTheCard_andMissingLabelFallsBackToTheName() {
        SchemaCard.Table table = builder.resolve("labelscope").stream()
                .filter(t -> t.name().equals("connector.v_labeltest"))
                .findFirst().orElseThrow();

        SchemaCard.Column code = table.columns().stream()
                .filter(col -> col.name().equals("customer_code")).findFirst().orElseThrow();
        SchemaCard.Column name = table.columns().stream()
                .filter(col -> col.name().equals("name")).findFirst().orElseThrow();

        assertThat(code.label()).isEqualTo("Code client");   // from the manifest
        assertThat(name.label()).isEqualTo("name");           // no label declared → canonical name
    }
}
