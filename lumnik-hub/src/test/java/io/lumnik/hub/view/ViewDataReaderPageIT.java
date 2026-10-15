package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Walking a View page by page (#777) against a real Postgres: every row exactly once, in the
 * contract's order, whatever the page size — through duplicated first-column values, NULLs, a
 * non-text first column, and (for a relation with no id, paged by position) rows identical on every shown column.
 */
@QuarkusTest
class ViewDataReaderPageIT {

    @Inject ViewDataReader reader;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static ResolvedView view(String table, String... colsAndTypes) {
        List<ResolvedView.Column> cols = new ArrayList<>();
        for (int i = 0; i < colsAndTypes.length; i += 2)
            cols.add(new ResolvedView.Column(colsAndTypes[i], colsAndTypes[i], colsAndTypes[i + 1]));
        return new ResolvedView("T", table, cols);
    }

    /** Every page, following {@code next} like a client would; the rows concatenated. */
    private List<Map<String,Object>> walk(ResolvedView rv, List<ViewFilter> filters, boolean hasId, int size) {
        List<Map<String,Object>> all = new ArrayList<>();
        ViewCursor.Boundary after = null;
        for (int guard = 0; guard < 1000; guard++) {
            ViewDataReader.PageResult r = reader.readPage(1L, rv, filters, hasId, new ViewDataReader.Page(size, after));
            assertThat(r.rows().size()).isLessThanOrEqualTo(size);
            all.addAll(r.rows());
            if (!r.hasMore()) {
                assertThat(r.next()).as("no cursor on the last page").isNull();
                return all;
            }
            assertThat(r.rows()).as("a page that has more is full").hasSize(size);
            after = r.next();
        }
        throw new AssertionError("the walk never ended");
    }

    private void seedIdTable() {
        exec("DROP TABLE IF EXISTS connector.t_page_id");
        exec("CREATE TABLE connector.t_page_id (id BIGSERIAL PRIMARY KEY, code text, n int)");
        // 3 distinct codes repeated, 6 NULL codes, a spread of ids; inserted out of order on purpose
        exec("INSERT INTO connector.t_page_id (code, n) SELECT "
           + "CASE WHEN g % 5 = 0 THEN NULL ELSE 'C-' || (g % 3) END, (g * 7) % 11 FROM generate_series(1, 31) g");
    }

    private List<Object> expectedIds(String orderBy) {
        List<Object> ids = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT id FROM connector.t_page_id ORDER BY " + orderBy)) {
            while (rs.next()) ids.add(rs.getLong(1));
        } catch (Exception e) { throw new RuntimeException(e); }
        return ids;
    }

    private static List<Object> col(List<Map<String,Object>> rows, String name) {
        return rows.stream().map(r -> r.get(name)).toList();
    }

    @Transactional
    @Test void idRelation_everyPageSize_servesEveryRowOnceInContractOrder() {
        seedIdTable();
        ResolvedView rv = view("connector.t_page_id", "code", "text", "n", "int4", "id", "bigint");
        List<Object> expected = expectedIds("code NULLS LAST, id");
        for (int size : new int[] {1, 2, 5, 7, 30, 31, 32, 200}) {
            assertThat(col(walk(rv, List.of(), true, size), "id")).as("page size " + size).isEqualTo(expected);
        }
    }

    @Transactional
    @Test void idRelation_nonTextFirstColumn_pagesInItsOwnTypeOrder() {
        seedIdTable();
        ResolvedView rv = view("connector.t_page_id", "n", "int4", "code", "text");
        // numeric order, not text order: 10 after 9
        assertThat(col(walk(rv, List.of(), true, 4), "n")).isEqualTo(
            col(walk(rv, List.of(), true, 1000), "n")).isSortedAccordingTo((a, b) -> ((Integer) a).compareTo((Integer) b));
        assertThat(col(walk(rv, List.of(), true, 4), "n").size()).isEqualTo(31);
    }

    @Transactional
    @Test void idRelation_firstColumnIsTheId_pagesOnIdAlone() {
        seedIdTable();
        ResolvedView rv = view("connector.t_page_id", "id", "bigint", "code", "text");
        assertThat(col(walk(rv, List.of(), true, 6), "id")).isEqualTo(expectedIds("id"));
    }

    @Transactional
    @Test void idRelation_withAFilter_pagesOnlyTheMatchingRows() {
        seedIdTable();
        ResolvedView rv = view("connector.t_page_id", "code", "text", "n", "int4");
        List<ViewFilter> f = List.of(new ViewFilter("code", "=", "C-1"));
        List<Map<String,Object>> rows = walk(rv, f, true, 3);
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r.get("code")).isEqualTo("C-1"));
        assertThat(rows).hasSize(walk(rv, f, true, 1000).size());
    }

    @Transactional
    @Test void idRelation_aDeletedBoundaryRow_isTheOneRefusal() {
        seedIdTable();
        ResolvedView rv = view("connector.t_page_id", "code", "text");
        ViewDataReader.PageResult first = reader.readPage(1L, rv, List.of(), true, new ViewDataReader.Page(3, null));
        exec("DELETE FROM connector.t_page_id WHERE id = " + first.next().values().get(0));
        assertThatThrownBy(() -> reader.readPage(1L, rv, List.of(), true, new ViewDataReader.Page(3, first.next())))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(ViewCursor.REFUSAL);
    }

    @Transactional
    @Test void noIdRelation_duplicatesNullsAndIdenticalRows_everyRowOnce() {
        exec("DROP TABLE IF EXISTS connector.t_page_noid");
        exec("CREATE TABLE connector.t_page_noid (a text, b text)");
        // 12 rows identical on both columns, 5 NULL/NULL, 5 with NULL b, and a spread of others
        exec("INSERT INTO connector.t_page_noid SELECT 'X', 'same' FROM generate_series(1, 12)");
        exec("INSERT INTO connector.t_page_noid SELECT NULL, NULL FROM generate_series(1, 5)");
        exec("INSERT INTO connector.t_page_noid SELECT 'X', NULL FROM generate_series(1, 5)");
        exec("INSERT INTO connector.t_page_noid SELECT 'A' || (g % 4), 'b' || (g % 3) FROM generate_series(1, 20) g");
        ResolvedView rv = view("connector.t_page_noid", "a", "text", "b", "text");
        List<Map<String,Object>> expected = walk(rv, List.of(), false, 1000);
        assertThat(expected).hasSize(42);
        List<String> expectedKeys = expected.stream().map(r -> r.get("a") + "|" + r.get("b")).toList();
        for (int size : new int[] {1, 2, 3, 5, 12, 13, 41, 42, 43}) {
            List<String> got = walk(rv, List.of(), false, size).stream().map(r -> r.get("a") + "|" + r.get("b")).toList();
            assertThat(got).as("page size " + size).isEqualTo(expectedKeys);
        }
    }

    @Transactional
    @Test void firstPageOfAnEmptyResult_isEmpty_withoutMore() {
        seedIdTable();
        ResolvedView rv = view("connector.t_page_id", "code", "text");
        ViewDataReader.PageResult r = reader.readPage(1L, rv, List.of(new ViewFilter("code", "=", "nope")), true,
            new ViewDataReader.Page(10, null));
        assertThat(r.rows()).isEmpty();
        assertThat(r.hasMore()).isFalse();
        assertThat(r.next()).isNull();
    }

    @Transactional
    @Test void exactlyOnePageOfRows_saysNoMore() {
        seedIdTable();
        ResolvedView rv = view("connector.t_page_id", "code", "text");
        ViewDataReader.PageResult r = reader.readPage(1L, rv, List.of(), true, new ViewDataReader.Page(31, null));
        assertThat(r.rows()).hasSize(31);
        assertThat(r.hasMore()).as("31 rows in 31: nothing beyond").isFalse();
    }
}
