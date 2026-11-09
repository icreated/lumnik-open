package io.lumnik.hub.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.sql.ManagedIndex.Spec;
import io.lumnik.hub.sql.ManagedIndex.State;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The generic managed index: built concurrently, repaired when a build was interrupted, never over a name that is not ours. */
@QuarkusTest
class ManagedIndexIT {

    @Inject ManagedIndex indexes;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private String table() throws Exception {
        String t = "t_mx_it_" + System.nanoTime();
        exec("CREATE TABLE connector." + t + " (tenant_id bigint NOT NULL, code text, price numeric, at timestamptz, d date)");
        return t;
    }

    private void exec(String sql) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void buildsTheIndexOnceAndSaysSo() throws Exception {
        Spec spec = new Spec("connector", table(), List.of("tenant_id", "trim_scale(price::numeric)"));   // a key that builds; whether it serves is refusal's question
        assertThat(indexes.state(spec)).isEqualTo(State.PENDING);

        indexes.ensure(spec);
        assertThat(indexes.state(spec)).isEqualTo(State.VALID);

        indexes.ensure(spec);   // nothing to do the second time
        assertThat(indexes.state(spec)).isEqualTo(State.VALID);
    }

    @Test
    void anInterruptedBuildIsDroppedThenRebuilt() throws Exception {
        Spec spec = new Spec("connector", table(), List.of("tenant_id", "code"));
        indexes.ensure(spec);
        exec("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'connector." + spec.name() + "'::regclass");
        assertThat(indexes.state(spec)).isEqualTo(State.PENDING);

        indexes.ensure(spec);   // drops the invalid one
        assertThat(indexes.state(spec)).isEqualTo(State.PENDING);
        indexes.ensure(spec);   // builds it again
        assertThat(indexes.state(spec)).isEqualTo(State.VALID);
    }

    @Test
    void anObjectThatIsNotOursUnderOurNameIsLeftAlone() throws Exception {
        String table = table();
        Spec spec = new Spec("connector", table, List.of("tenant_id", "code"));
        exec("CREATE INDEX " + spec.name() + " ON connector." + table + " (code)");

        indexes.ensure(spec);

        assertThat(indexes.state(spec)).as("a one-column index is not (tenant_id, code)").isEqualTo(State.PENDING);
    }

    @Test
    void anIndexOfOurNameWhoseFirstKeyIsNotTheTenantIsNotOurs() throws Exception {
        String table = table();
        Spec spec = new Spec("connector", table, List.of("tenant_id", "code"));
        exec("CREATE INDEX " + spec.name() + " ON connector." + table + " (code, tenant_id)");

        indexes.ensure(spec);

        assertThat(indexes.state(spec)).as("two keys, but not (tenant_id, code): left alone, not taken for ours").isEqualTo(State.PENDING);
    }

    /** Same name, same arity, same first key: only the second key differs. Not ours, so neither accepted nor dropped. */
    @Test
    void anIndexOfOurNameWithAnotherSecondKeyIsNeitherAcceptedNorDropped() throws Exception {
        String table = table();
        Spec spec = new Spec("connector", table, List.of("tenant_id", "code"));
        exec("CREATE INDEX " + spec.name() + " ON connector." + table + " (tenant_id, price)");
        exec("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'connector." + spec.name() + "'::regclass");

        indexes.ensure(spec);

        assertThat(indexes.state(spec)).isEqualTo(State.PENDING);
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname = 'connector' AND indexname = '" + spec.name() + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("a foreign object under our name is not ours to drop").isEqualTo(1);
        }
    }

    @Test
    void aTableThatDoesNotExistYetIsNotAnError() throws Exception {
        Spec spec = new Spec("connector", "t_mx_it_absent", List.of("tenant_id", "code"));
        indexes.ensure(spec);
        assertThat(indexes.state(spec)).isEqualTo(State.PENDING);
    }

    @Test
    void refusesAnExpressionPostgresCannotIndexAndSaysWhy() throws Exception {
        // timestamptz::text depends on the session's zone: STABLE, not IMMUTABLE
        assertThat(indexes.refusal(new Spec("connector", table(), List.of("tenant_id", "(at::text)"))))
            .hasValueSatisfying(why -> assertThat(why).contains("depends on the session").doesNotContain("\""));
    }

    /** Under RLS a predicate on a function that is not LEAKPROOF is never an index condition: the index would be built for nothing. */
    @Test
    void refusesAKeyThatRowLevelSecurityWouldNeverLetTheIndexServe() throws Exception {
        String table = table();
        assertThat(indexes.refusal(new Spec("connector", table, List.of("tenant_id", "trim_scale(price::numeric)"))))
            .hasValueSatisfying(why -> assertThat(why).contains("connector." + table).contains("trim_scale").contains("row-level security"));
        assertThat(indexes.refusal(new Spec("connector", table, List.of("tenant_id", "(d::timestamp AT TIME ZONE 'UTC')"))))
            .hasValueSatisfying(why -> assertThat(why).contains("timezone"));
    }

    @Test
    void acceptsAPlainColumnKey() throws Exception {
        assertThat(indexes.refusal(new Spec("connector", table(), List.of("tenant_id", "code")))).isEmpty();
    }
}
