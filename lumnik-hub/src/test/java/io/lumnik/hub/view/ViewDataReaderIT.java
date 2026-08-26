package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reader's OWN logic — column projection, table resolution, ordering — read through the
 * readonly datasource. Tenant isolation (SET LOCAL app.current_tenant + empty search_path) is
 * copied verbatim from AskToSqlAgent.runReadOnly and covered there; in %test the readonly DS runs
 * as lumnik_app with transactions disabled, so SET LOCAL cannot be exercised end-to-end here (the
 * same reason RagChatAnalyticIT seeds a plain table). This IT asserts the reader's behavior
 * through the query results — only the declared columns come back, ordered by the first one.
 */
@QuarkusTest
class ViewDataReaderIT {

    @Inject ViewDataReader reader;

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_demo "
           + "(customer_code text, name text, city text)");
        exec("TRUNCATE connector.t_view_demo");
        exec("INSERT INTO connector.t_view_demo VALUES "
           + "('C-2','Bob','Paris'), ('C-1','Alice','Lyon'), ('C-3','100% Discount Co','Nîmes')");

        exec("DROP TABLE IF EXISTS connector.t_view_nums");
        exec("CREATE TABLE connector.t_view_nums (code text, amount text, due text)");
        exec("INSERT INTO connector.t_view_nums VALUES "
           + "('BIG','150','2026-09-01'), ('SMALL','50','2026-07-01'), ('DIRTY','n/a','soon'), "
           // amount 100 sits exactly outside all four numeric-test boundaries below (not >100,
           // not >=150, not <100, not <=50) so it cannot disturb numericThresholdMatchesExcludes...;
           // only its due '0000-00-00' matters here, and that now fails the tightened shape.
           + "('ZERO','100','0000-00-00')");

        exec("DROP TABLE IF EXISTS connector.t_view_baddate");
        exec("CREATE TABLE connector.t_view_baddate (code text, due text)");
        exec("INSERT INTO connector.t_view_baddate VALUES ('X','2026-02-30')");  // shape-valid, calendar-invalid
    }

    // Registry case: the 1000-row cap used to cut silently — a métier user seeing exactly
    // 1000 rows could not tell a full result from a truncated one ("a view never lies").
    @Transactional
    @Test void beyondTheCap_rowsCutAndTheResponseSaysSo() {
        exec("DROP TABLE IF EXISTS connector.t_view_many");
        exec("CREATE TABLE connector.t_view_many (code text)");
        exec("INSERT INTO connector.t_view_many "
           + "SELECT 'C-' || lpad(g::text, 5, '0') FROM generate_series(1, 1001) g");
        ViewDataReader.ReadResult r = reader.read(1L,
            new ResolvedView("Many", "connector.t_view_many",
                List.of(new ResolvedView.Column("code", "Code", "text"))),
            List.of());
        assertThat(r.rows()).hasSize(1000);
        assertThat(r.truncated()).as("the cut must be spoken, never silent").isTrue();
    }

    @Transactional
    @Test void underTheCap_truncatedIsFalse() {
        ViewDataReader.ReadResult r = reader.read(1L, demoView(), List.of());
        assertThat(r.rows()).hasSize(3);
        assertThat(r.truncated()).isFalse();
    }

    private ResolvedView demoView() {
        return new ResolvedView("Demo", "connector.t_view_demo", List.of(
                new ResolvedView.Column("customer_code", "Code", "text"),
                new ResolvedView.Column("name", "Name", "text")));
    }

    private ResolvedView numsView() {
        return new ResolvedView("Nums", "connector.t_view_nums", List.of(
                new ResolvedView.Column("code", "Code", "text"),
                new ResolvedView.Column("amount", "Amount", "text"),
                new ResolvedView.Column("due", "Due", "text")));
    }

    private ResolvedView badDateView() {
        return new ResolvedView("Bad", "connector.t_view_baddate", List.of(
                new ResolvedView.Column("code", "Code", "text"),
                new ResolvedView.Column("due", "Due", "text")));
    }

    @Test
    @Transactional
    void selectsOnlyDeclaredColumnsOrderedByTheFirst() {
        List<Map<String,Object>> rows = reader.read(1L, demoView(), List.of()).rows();

        assertThat(rows).hasSize(3);
        // only the two declared columns — city is never selected
        assertThat(rows).allSatisfy(r -> assertThat(r).containsOnlyKeys("customer_code", "name"));
        // ordered by the first declared column
        assertThat(rows).extracting(r -> r.get("customer_code")).containsExactly("C-1", "C-2", "C-3");
        assertThat(rows.get(0).get("name")).isEqualTo("Alice");
    }

    @Test
    @Transactional
    void equalityFilterMatchesExactly() {
        var rows = reader.read(1L, demoView(), List.of(new ViewFilter("name", "=", "Alice"))).rows();
        assertThat(rows).extracting(r -> r.get("customer_code")).containsExactly("C-1");
    }

    @Test
    @Transactional
    void containsFilterIsCaseInsensitive_andFiltersAnd() {
        assertThat(reader.read(1L, demoView(), List.of(new ViewFilter("name", "~", "ali"))).rows())
                .extracting(r -> r.get("customer_code")).containsExactly("C-1");
        assertThat(reader.read(1L, demoView(), List.of(
                new ViewFilter("name", "~", "o"), new ViewFilter("customer_code", "=", "C-2"))).rows())
                .extracting(r -> r.get("customer_code")).containsExactly("C-2");
    }

    @Test
    @Transactional
    void percentInAContainsValueIsLiteral() {
        var rows = reader.read(1L, demoView(), List.of(new ViewFilter("name", "~", "100%"))).rows();
        assertThat(rows).extracting(r -> r.get("customer_code")).containsExactly("C-3");
    }

    @Test
    @Transactional
    void numericThresholdMatchesExcludes_andDirtyTextNeverMatchesNor500s() {
        // amount>100 → only the 150 row; the 'n/a' row never matches and never crashes
        assertThat(reader.read(1L, numsView(), List.of(ViewFilter.parse("amount>100"))).rows())
                .extracting(r -> r.get("code")).containsExactly("BIG");
        // >= includes the boundary
        assertThat(reader.read(1L, numsView(), List.of(ViewFilter.parse("amount>=150"))).rows())
                .extracting(r -> r.get("code")).containsExactly("BIG");
        // <100 finds only the 50 row ('n/a' is incomparable, not "small")
        assertThat(reader.read(1L, numsView(), List.of(ViewFilter.parse("amount<100"))).rows())
                .extracting(r -> r.get("code")).containsExactly("SMALL");
        // typed, not text: text comparison would put '50' > '150' (5>1) — must NOT match >100
        assertThat(reader.read(1L, numsView(), List.of(ViewFilter.parse("amount<=50"))).rows())
                .extracting(r -> r.get("code")).containsExactly("SMALL");
    }

    @Test
    @Transactional
    void temporalThresholdComparesIsoDates_dirtyNeverMatches() {
        assertThat(reader.read(1L, numsView(), List.of(ViewFilter.parse("due<2026-08-01"))).rows())
                .extracting(r -> r.get("code")).containsExactly("SMALL");   // only 2026-07-01; 'soon' no-match
        assertThat(reader.read(1L, numsView(), List.of(ViewFilter.parse("due>=2026-08-01"))).rows())
                .extracting(r -> r.get("code")).containsExactly("BIG");
    }

    @Test
    @Transactional
    void aZeroDateRowIsSilentlyExcludedFromATemporalFilter_no500() {
        // 0000-00-00 fails the tightened TEMPORAL_SHAPE → ELSE false → excluded, exactly like
        // dirty text 'soon'; the in-memory world excludes it too (parseTemporal → null). No 500.
        assertThat(reader.read(1L, numsView(), List.of(ViewFilter.parse("due<2026-08-01"))).rows())
                .extracting(r -> r.get("code")).containsExactly("SMALL");   // only 2026-07-01
    }

    @Test
    @Transactional
    void aCalendarInvalidDateRowIsRefusedNotA500() {
        // 2026-02-30 passes the shape (day 30 is in 01-31) but PG rejects the timestamptz cast;
        // ViewDataReader maps SQLState 22007/22008 to IllegalArgumentException → the resource 400s.
        assertThatThrownBy(
                () -> reader.read(1L, badDateView(), List.of(ViewFilter.parse("due<2026-08-01"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void rejectsARawNonOrderingOperatorBeforeItReachesSql() {
        // a ViewFilter built OUTSIDE parse() carries an unvalidated op — sqlFor must refuse it,
        // not splice it into SQL (defense-in-depth at the op→SQL boundary)
        ViewFilter evil = new ViewFilter("amount", "; DROP TABLE x --", "1");
        assertThatThrownBy(() -> reader.read(1L, numsView(), List.of(evil)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @Transactional
    void rejectsARawOrderingFilterWithANonOrderedValue() {
        // ordering op but operandType()==null (a value that isn't a number or ISO date), built
        // outside parse() — sqlFor must refuse it locally, not bind it into a misleading cast
        ViewFilter bad = new ViewFilter("amount", ">", "abc");
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> reader.read(1L, numsView(), List.of(bad)))
            .isInstanceOf(RuntimeException.class);
    }
}
