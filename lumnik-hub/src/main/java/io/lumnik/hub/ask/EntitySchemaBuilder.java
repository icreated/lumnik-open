package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovery source for fused ENTITY views. Entities live in connector.entity_manifest (global,
 * not per-connector) and declare which métier scopes can see them. For a scope, emit one
 * SchemaCard.Table per matching entity, pointing at its connector.v_&lt;name&gt; view with the
 * manifest's canonical columns. No catalog scan — columns come from the manifest.
 */
@ApplicationScoped
public class EntitySchemaBuilder {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject EntityManifestParser parser;

    /** Entity views visible in this scope, as SchemaCard tables (connector.v_&lt;name&gt; + canonical cols). */
    public List<SchemaCard.Table> resolve(String scope) {
        List<SchemaCard.Table> out = new ArrayList<>();
        String sql = "SELECT name, yaml_text FROM connector.entity_manifest "
            + "WHERE scopes && ARRAY[?]::text[] ORDER BY name";
        try (Connection cx = adminDs.getConnection();
             PreparedStatement ps = cx.prepareStatement(sql)) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name"); // stored lower-cased
                    EntityManifest m = parser.parse(rs.getString("yaml_text"));
                    List<SchemaCard.Column> cols = new ArrayList<>();
                    for (String c : m.canonicalColumns()) {
                        EntityManifest.ColumnMeta meta = m.columns().get(c);
                        if (meta == null) {
                            cols.add(new SchemaCard.Column(c, "text", c));
                        } else {
                            cols.add(new SchemaCard.Column(c, "text", or(meta.label(), c),
                                meta.description(), meta.example(), meta.values()));
                        }
                    }
                    // The table's LABEL is the entity's `label:` when it declares one, and its
                    // name otherwise — the same ladder the per-column label above walks. A
                    // kind:View with no `title:` falls back to exactly this, so before the
                    // manifest could carry one the ladder's every rung was the technical name
                    // and a generated view was headed `acmecustomer` (#501).
                    out.add(new SchemaCard.Table(
                        "connector.v_" + name, or(m.label(), name), cols, m.description()));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("EntitySchemaBuilder.resolve(scope=" + scope + "): " + e, e);
        }
        return out;
    }

    /**
     * The declared display name, or the technical one — the ladder both a column's {@code label:}
     * and an entity's own walk. A blank is an absent label, not an empty one: YAML's {@code label:}
     * with nothing after it must not make a card whose heading is a space.
     */
    private static String or(String label, String fallback) {
        return (label != null && !label.isBlank()) ? label : fallback;
    }
}
