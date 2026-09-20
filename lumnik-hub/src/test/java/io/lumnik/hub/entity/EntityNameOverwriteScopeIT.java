package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name-overwrite guard's scope predicate, asked of the COMPILER rather than of the door.
 *
 * <p>{@code EntityApplyResource} calls {@code registry.storedScopes(name)} and then
 * {@code compiler.apply} — two connections, two
 * transactions, and a window in between where another transaction can take that name under a scope
 * the caller does not hold. The property that survives that race is not "the door refuses" but
 * <b>the store refuses on its own</b>, so the write is called directly here, as if the ordered check
 * had been jumped entirely — which is precisely what the race does. The full reasoning lives in
 * {@code AppNameOverwriteScopeIT}; this is its kind:Entity sibling (#582).
 *
 * <p>Entity is the multi-scope kind, so the predicate is containment ({@code <@}) rather than
 * membership, and it has to agree with {@code ScopeGrants.allowsAll}: every stored tag held, and at
 * least one — an overlap is not enough, and a scopeless row is not a bound caller's to overwrite.
 * Those two are the last two tests here.
 *
 * <p>The refusal must also undo the DDL. {@code apply} runs the guarded upsert and DROP/CREATE VIEW
 * in ONE transaction, so a refusal that left the view rebuilt would have published a foreign
 * métier's fusion under this caller's shape while claiming nothing was written.
 */
@QuarkusTest
class EntityNameOverwriteScopeIT {

    private static final String OWNER = "proprietaire-ent-582";
    private static final String SIBLING = "voisin-ent-582";
    private static final String INTRUDER = "intrus-ent-582";

    @Inject EntityViewCompiler compiler;
    @Inject EntityRegistry registry;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @BeforeEach
    void seedSourceTable() throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS connector.t_ent_582 "
                + "(tenant_id bigint NOT NULL, code text, libelle text)");
        }
    }

    /**
     * The YAML is the ONE source: the manifest handed to the compiler is parsed back out of it, the
     * ProcessNameOverwriteScopeIT idiom. A hand-built record beside the text is a second thing to
     * keep in agreement, and it drifts silently — the stored-yaml assertion below would still pass.
     *
     * <p>It must be a REAL manifest: {@code EntityRegistry.loadFromDb} parses every
     * {@code yaml_text} in the table, so one unparseable row left behind here would fail the
     * registry for every other test sharing the database.
     */
    private static String yaml(String name, String label, List<String> scopes) {
        return """
            kind: Entity
            name: %s
            label: %s
            match: [code]
            sources:
              - table: t_ent_582
                map:
                  code: code
                  libelle: libelle
            scopes: [%s]
            """.formatted(name, label, String.join(", ", scopes));
    }

    /** Applies this manifest as a caller holding {@code grants} ({@code null} = unscoped). */
    private ScopeGuardedUpsert apply(String name, String label, List<String> scopes,
                                     Set<String> grants) {
        String text = yaml(name, label, scopes);
        return compiler.apply(new EntityManifestParser().parse(text), text, grants);
    }

    /** Puts the name in {@link #OWNER}'s hands, as an unscoped integrator legitimately would. */
    private void given(String name, List<String> scopes) {
        apply(name, "Owned", scopes, null);
    }

    private Optional<String> storedYaml(String name) {
        return Jdbc.queryOne(adminDs, "EntityNameOverwriteScopeIT.storedYaml",
            "SELECT yaml_text FROM connector.entity_manifest WHERE name = ?",
            ps -> ps.setString(1, name), rs -> rs.getString(1));
    }

    @Test
    void aBoundCallerCannotOverwriteANameHeldOutsideItsGrants() {
        given("ent_nom_582", List.of(OWNER));

        ScopeGuardedUpsert outcome =
            apply("ent_nom_582", "Stolen", List.of(INTRUDER), Set.of(INTRUDER));

        assertThat(outcome)
            .as("the conflict arm's own WHERE refuses, with no help from the resource's ordered "
                + "storedScopes check")
            .isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);

        assertThat(registry.storedScopes("ent_nom_582"))
            .as("refused AND untouched — the row must not have been re-homed on the way out")
            .contains(List.of(OWNER));
        assertThat(storedYaml("ent_nom_582")).contains(yaml("ent_nom_582", "Owned", List.of(OWNER)));
    }

    @Test
    void anUnscopedCallerStillOverwritesFreely() {
        // Half a rule proven is a rule that could be enforced by refusing everything.
        given("ent_relais_582", List.of(OWNER));

        ScopeGuardedUpsert outcome =
            apply("ent_relais_582", "Re-homed", List.of(INTRUDER), null);

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(registry.storedScopes("ent_relais_582")).contains(List.of(INTRUDER));
    }

    @Test
    void aBoundCallerStillCreatesAFreeName() {
        // The third outcome of INSERT … ON CONFLICT: no conflict at all. Zero rows affected is read
        // as a refusal, so an ordinary insert must never be mistaken for one.
        ScopeGuardedUpsert outcome =
            apply("ent_neuf_582", "Neuf", List.of(INTRUDER), Set.of(INTRUDER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(registry.storedScopes("ent_neuf_582")).contains(List.of(INTRUDER));
    }

    @Test
    void aBoundCallerMayOverwriteANameWhoseScopesItAllHolds() {
        // The guard must not refuse the ordinary case it exists to permit.
        given("ent_sien_582", List.of(OWNER, SIBLING));

        ScopeGuardedUpsert outcome =
            apply("ent_sien_582", "Updated", List.of(OWNER), Set.of(OWNER, SIBLING));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.Applied.class);
        assertThat(storedYaml("ent_sien_582")).contains(yaml("ent_sien_582", "Updated", List.of(OWNER)));
    }

    @Test
    void anOverlapIsNotEnoughToOverwrite() {
        // allowsAll, not allowsAny: taking over a manifest tagged [owner, sibling] while holding
        // only owner publishes into sibling. The containment predicate has to say the same thing
        // the door's ScopeGrants.allowsAll does — `<@`, not `&&`.
        given("ent_chevauche_582", List.of(OWNER, SIBLING));

        ScopeGuardedUpsert outcome =
            apply("ent_chevauche_582", "Narrowed", List.of(OWNER), Set.of(OWNER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);
        assertThat(registry.storedScopes("ent_chevauche_582")).contains(List.of(OWNER, SIBLING));
    }

    @Test
    void aScopelessRowIsNotABoundCallersToOverwrite() {
        // `'{}' <@ anything` is TRUE, so containment alone would hand every scopeless entity to
        // every bound caller. ScopeGrants.allowsAll refuses an empty list; the cardinality clause
        // is what makes the SQL agree.
        given("ent_sans_scope_582", List.of());

        ScopeGuardedUpsert outcome =
            apply("ent_sans_scope_582", "Claimed", List.of(INTRUDER), Set.of(INTRUDER));

        assertThat(outcome).isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);
        assertThat(registry.storedScopes("ent_sans_scope_582")).contains(List.of());
    }
}
