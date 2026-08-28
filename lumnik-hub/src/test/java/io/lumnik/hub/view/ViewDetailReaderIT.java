package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ViewDetailReaderIT {

    @Inject ViewDetailReader reader;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        // fused-style table WITH a _conflicts ledger column
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_detail_demo "
           + "(customer_code text, name text, _conflicts text)");
        exec("TRUNCATE connector.t_view_detail_demo");
        exec("INSERT INTO connector.t_view_detail_demo VALUES "
           + "('C-200','Marie','{\"name\": [{\"src\":\"cust_siebel\",\"value\":\"Marie\"},"
           + "{\"src\":\"t_cust_csv\",\"value\":\"MARIE A.\"}]}')");
        // raw table WITHOUT a _conflicts column
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_detail_raw (customer_code text, name text)");
        exec("TRUNCATE connector.t_view_detail_raw");
        exec("INSERT INTO connector.t_view_detail_raw VALUES ('C-1','Solo')");
        // duplicate-key table for the ambiguity case
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_detail_dup (customer_code text, name text)");
        exec("TRUNCATE connector.t_view_detail_dup");
        exec("INSERT INTO connector.t_view_detail_dup VALUES ('DUP','A'), ('DUP','B')");
    }

    private ResolvedView view(String table) {
        return new ResolvedView("Demo", table, List.of(
                new ResolvedView.Column("customer_code", "Code", "text"),
                new ResolvedView.Column("name", "Name", "text")));
    }

    @Test
    @Transactional
    void readsOneRecordWithItsLedger() {
        ViewDetailReader.DetailRead r = reader.read(1L, view("connector.t_view_detail_demo"), "C-200");
        assertThat(r.ledgerPresent()).isTrue();
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).values()).containsExactly("C-200", "Marie");
        assertThat(r.rows().get(0).conflictsJson()).contains("MARIE A.");
    }

    @Test
    @Transactional
    void rawTableHasNoLedger() {
        ViewDetailReader.DetailRead r = reader.read(1L, view("connector.t_view_detail_raw"), "C-1");
        assertThat(r.ledgerPresent()).isFalse();
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).values()).containsExactly("C-1", "Solo");
        assertThat(r.rows().get(0).conflictsJson()).isNull();
    }

    @Test
    @Transactional
    void ambiguousKeyReturnsTwoRows() {
        ViewDetailReader.DetailRead r = reader.read(1L, view("connector.t_view_detail_dup"), "DUP");
        assertThat(r.rows()).hasSize(2);   // LIMIT 2 → the resource maps this to 400
    }

    @Test
    @Transactional
    void unknownKeyReturnsNoRows() {
        ViewDetailReader.DetailRead r = reader.read(1L, view("connector.t_view_detail_raw"), "GHOST");
        assertThat(r.rows()).isEmpty();
    }
}
