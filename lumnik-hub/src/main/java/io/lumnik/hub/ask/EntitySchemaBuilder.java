package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovery source for fused ENTITY views. Entities live in connector.entity_manifest (global,
 * not per-connector) and declare which métier scopes can see them. For a scope, emit one
 * SchemaCard.Table per matching entity, pointing at its connector.v_&lt;name&gt; view with the
 * manifest's canonical columns. The columns come from the manifest, their types from the compiled view.
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
                    // The compiled view's own types (#1325): a date the sources agree on is a date here,
                    // so a view filters it and Ask writes SQL for it as on a table. Text before compiling.
                    Map<String, String> types = EntityViewCompiler.viewColumnTypes(cx, name);
                    List<SchemaCard.Column> cols = new ArrayList<>();
                    for (String c : m.canonicalColumns()) {
                        EntityManifest.ColumnMeta meta = m.columns().get(c);
                        String type = types.getOrDefault(c, "text");
                        if (meta == null) {
                            cols.add(new SchemaCard.Column(c, type, c));
                        } else {
                            cols.add(new SchemaCard.Column(c, type, or(meta.label(), c),
                                meta.description(), meta.example(), meta.values()));
                        }
                    }
                    if (m.resolution() != null) {
                        addResolutionColumns(cols);
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

    /**
     * {@code _entity_id}/{@code _resolution} (#622) — {@link
     * io.lumnik.hub.entity.EntityViewCompiler#appendResolvedSourceBranch} already computes both on
     * the compiled view for every resolution-bearing entity, via a LEFT JOIN so an unresolved row
     * is never dropped; only a card declaration was missing. Unlike {@code _conflicts} (a jsonb
     * blob kept off the card on purpose — see {@code AbstentionGuard}), {@code _resolution} is a
     * plain scalar enum, safe to declare with a domain so the model can honestly answer "how many
     * could not be resolved" by grouping on a real, declared fact instead of inventing one.
     *
     * <p>The domain deliberately does NOT list {@code null} as an allowed value — {@code
     * DomainGuard} checks literal filters, and {@code NULL} is never a literal one writes on the
     * right of {@code =}. The caveat instead lives in the description text, where a model reads it
     * before deciding how to phrase a count: the LEFT JOIN means a row with no resolution pass yet
     * carries {@code _resolution IS NULL}, not one of the four states — a query that only branches
     * on those four would silently omit exactly the rows the "how many were not resolved" question
     * is asking about.
     */
    private static void addResolutionColumns(List<SchemaCard.Column> cols) {
        cols.add(new SchemaCard.Column("_entity_id", "uuid", "Resolved entity",
            "The canonical entity this row resolved to. NULL before the first resolution pass, "
                + "or whenever _resolution is not 'resolved'.",
            null, List.of()));
        cols.add(new SchemaCard.Column("_resolution", "text", "Resolution state",
            "How this row's identity resolution currently stands. NULL means no resolution pass "
                + "has processed this row yet — treat NULL together with the non-resolved states "
                + "when counting rows that are not resolved, or the count will silently omit rows "
                + "still waiting for a pass.",
            "resolved", List.of("resolved", "ambiguous", "unresolved_no_rule", "unresolved_provider")));
    }
}
