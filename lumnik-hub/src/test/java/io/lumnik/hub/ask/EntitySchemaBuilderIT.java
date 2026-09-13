package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manifest's {@code label:} must flow into the SchemaCard — per column, and for the table.
 *
 * <p>Per column, this is the enrichment that lets a kind:View render "Code client" instead of the
 * raw canonical name; a column with no label falls back to the canonical name.
 *
 * <p>For the TABLE it is newer, and it is what gives the kind:View title ladder a human rung.
 * That ladder reads {@code spec.title}, then the table's label, then the table's name — and a
 * fused entity had no way to declare a label, so the middle rung WAS the name and a view with no
 * title was headed {@code acmecustomer} (#501).
 */
@QuarkusTest
class EntitySchemaBuilderIT {

    @Inject EntitySchemaBuilder builder;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static final String MANIFEST = """
        kind: Entity
        name: LabelTest
        label: "Clients ACME"
        match: [customer_code]
        scopes: [labelscope]
        columns:
          customer_code:
            label: "Code client"
        sources:
          - table: t_lbl_src
            map: { code: customer_code, nom: name }
        """;

    /** The same entity with no {@code label:} at all — the fallback rung of the ladder. */
    private static final String UNLABELLED = MANIFEST
        .replace("name: LabelTest", "name: NoLabelTest")
        .replace("label: \"Clients ACME\"\n", "");   // a text block strips the common indent

    @BeforeEach
    void seed() {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM connector.entity_manifest WHERE name IN ('labeltest', 'nolabeltest')");
        } catch (Exception e) { throw new RuntimeException(e); }
        store("labeltest", MANIFEST);
        store("nolabeltest", UNLABELLED);
    }

    private void store(String name, String manifest) {
        String sql = "INSERT INTO connector.entity_manifest (name, yaml_text, scopes) "
                   + "VALUES (?, ?, ARRAY['labelscope']::text[])";
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, manifest);
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
        assertThat(table.label()).as("the entity's own display name, for a view that declares no title")
                .isEqualTo("Clients ACME");
    }

    @Test
    void anEntityThatDeclaresNoLabelKeepsItsNameAsTheCardsLabel() {
        SchemaCard.Table table = builder.resolve("labelscope").stream()
                .filter(t -> t.name().equals("connector.v_nolabeltest"))
                .findFirst().orElseThrow();

        assertThat(table.label()).as("the last rung: what a view was headed before `label:` existed")
                .isEqualTo("nolabeltest");
    }
}
