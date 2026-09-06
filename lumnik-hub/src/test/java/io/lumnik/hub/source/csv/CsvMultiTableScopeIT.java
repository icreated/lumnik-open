package io.lumnik.hub.source.csv;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlGuard;
import io.lumnik.hub.api.sources.CsvIngestForm;
import io.lumnik.hub.ask.LeakGuard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.ask.UndocumentedColumnGuard;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two one-shot ingests, one scope, one question that joins them.
 *
 * <p>A scope's card is built by walking every connector carrying the scope as a tag, so two
 * one-shots sharing {@code --scope} land in ONE card and a cross-table question becomes
 * expressible. That was true by construction and untested: a scope carried several tables
 * only because someone had ingested several, never because anything required it. One
 * {@code seenFqn} short-circuit, one narrowed tag query, and the second table would vanish
 * from the card with every single-table test still green — the ask would simply answer
 * "the question doesn't map to the declared schema" and look like a model's judgement call.
 *
 * <p>The three assertions are the three things a joining question needs, and each fails on
 * its own account:
 * <ol>
 *   <li>the card carries BOTH tables and the key that links them;</li>
 *   <li>the honesty guards let a legitimate cross-table join through — the machinery built to
 *       refuse a cross-scope read must not refuse a within-scope join, which is the
 *       over-block half of the asymmetry of harm;</li>
 *   <li>the join executes through the readonly datasource and counts the right rows.</li>
 * </ol>
 *
 * <p>No model is involved. What a model does with this card is its own business — these are
 * the conditions under which its answer can be right at all.
 *
 * <p><b>What the third probe does NOT prove.</b> Under {@code %test} the readonly datasource
 * connects as {@code lumnik_app} and does not enlist in JTA — deliberately, and pinned by
 * {@code ReadonlyDatasourceEnlistmentContractTest}, whose javadoc states the consequence
 * plainly: the test profile cannot reproduce the production posture. So neither the
 * {@code lumnik_readonly} grants nor RLS are exercised here, and the {@code TenantRlsScope}
 * call below is production-shaped rather than load-bearing. That gap is real and older than
 * this test — nothing in the suite proves {@code lumnik_readonly} can read {@code ext.*},
 * which the one-shot spec asked for; closing it means the RLS-enforced harness, and it
 * belongs to its own arc.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsvMultiTableScopeIT {

    private static final String SCOPE = "joinprobe";
    private static final String CUSTOMERS = "ext.joinprobe_customer";
    private static final String ORDERS = "ext.joinprobe_order";

    /** The joining question, as SQL: how many orders does one named customer have? */
    private static final String JOINING_SQL =
        "SELECT count(*) FROM " + ORDERS + " o "
        + "JOIN " + CUSTOMERS + " c ON o.customer_id = c.customer_id "
        + "WHERE c.name = 'ACME'";

    @Inject CsvIngestService service;
    @Inject SchemaCardStore cards;
    @Inject LeakGuard leakGuard;
    @Inject UndocumentedColumnGuard columnGuard;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    @BeforeAll
    void ingestTwoTablesIntoOneScope() throws Exception {
        // ACME has two orders, GLOBEX one: a join that loses its WHERE, or fans out, counts 3 or 6.
        ingest("joinprobe_customer", "customer_id,name\n7,ACME\n8,GLOBEX\n");
        ingest("joinprobe_order", "order_id,customer_id,amount\n100,7,10\n101,7,20\n102,8,5\n");
    }

    private void ingest(String name, String csv) throws Exception {
        Path file = Files.createTempFile(name, ".csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        file.toFile().deleteOnExit();

        CsvIngestForm form = new CsvIngestForm();
        form.file = file.toFile();
        form.name = name;
        form.scope = SCOPE;

        try {
            // Committed like a real request: the card is rebuilt after commit, never before.
            QuarkusTransaction.requiringNew().call(() ->
                TenantContext.callAsSystem(() -> service.execute(form, 1L)));
        } finally {
            // execute() sets the tenant ThreadLocal and never clears it; callAsSystem restores
            // only SYSTEM_MODE (callAs is the one that saves and restores the holder). Left as is,
            // tenant 1 rides this JUnit thread out of @BeforeAll and into whatever runs next.
            CurrentTenantHolder.clear();
        }
    }

    private SchemaCard card() {
        return cards.load(1L, SCOPE).orElseThrow(
            () -> new AssertionError("no card for scope " + SCOPE + " — the ask would refuse outright"));
    }

    @Test
    void the_scope_card_carries_both_tables_and_the_key_that_links_them() {
        SchemaCard card = card();

        assertThat(card.tableNames())
            .as("a second one-shot under the same scope joins the first one's card")
            .contains(CUSTOMERS, ORDERS);

        assertThat(card.tables().stream()
                .filter(t -> t.name().equals(CUSTOMERS) || t.name().equals(ORDERS))
                .filter(t -> t.columns().stream().anyMatch(c -> c.name().equals("customer_id")))
                .count())
            .as("the join key must be declared on BOTH sides — one side alone is unjoinable")
            .isEqualTo(2);
    }

    @Test
    void the_guards_let_a_legitimate_cross_table_join_through() {
        SchemaCard card = card();
        String safeSql = new SqlGuard(500).check(JOINING_SQL);

        assertThat(leakGuard.check(safeSql, card))
            .as("both tables are declared by this very card — refusing here would be an over-block")
            .isEmpty();
        assertThat(columnGuard.check(safeSql, card))
            .as("the filtered column is declared; a cross-table question must not read as a leak")
            .isEmpty();
    }

    @Test
    void the_join_executes_and_counts_only_that_customer() {
        String safeSql = new SqlGuard(500).check(JOINING_SQL);

        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = readonlyDs.getConnection()) {
                // Called the way production calls it. Under %test its SET LOCALs are discarded
                // (see the class javadoc) — the shape is right, the guarantee is not here.
                TenantRlsScope.applyTo(c, 1L);
                try (PreparedStatement ps = c.prepareStatement(safeSql);
                     ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1))
                        .as("ACME's two orders — not GLOBEX's, and not the cartesian six")
                        .isEqualTo(2L);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
