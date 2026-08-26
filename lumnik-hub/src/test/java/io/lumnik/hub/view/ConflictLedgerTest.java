package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ConflictLedgerTest {

    private final ResolvedView rv = new ResolvedView("Clients", "connector.v_customer", List.of(
            new ResolvedView.Column("customer_code", "Code client", "text"),
            new ResolvedView.Column("name", "Nom", "text")));

    @Test
    void parsesConflictsWithLabelFromTheView() {
        String json = "{\"name\": [{\"src\":\"cust_siebel\",\"value\":\"Marie\"},"
                    + "{\"src\":\"t_cust_csv\",\"value\":\"MARIE A.\"}]}";
        List<ConflictLedger.Conflict> conflicts = ConflictLedger.parse(json, rv);

        assertThat(conflicts).hasSize(1);
        ConflictLedger.Conflict c = conflicts.get(0);
        assertThat(c.column()).isEqualTo("name");
        assertThat(c.label()).isEqualTo("Nom");               // labelled from the view
        assertThat(c.sources()).containsExactly(
                new ConflictLedger.SourceValue("cust_siebel", "Marie"),
                new ConflictLedger.SourceValue("t_cust_csv", "MARIE A."));
    }

    @Test
    void emptyLedgerAndNullYieldNoConflicts() {
        assertThat(ConflictLedger.parse("{}", rv)).isEmpty();
        assertThat(ConflictLedger.parse(null, rv)).isEmpty();
        assertThat(ConflictLedger.parse("   ", rv)).isEmpty();
    }

    @Test
    void aColumnNotShownByTheViewLabelsByItsOwnName() {
        String json = "{\"phone\": [{\"src\":\"a\",\"value\":\"1\"},{\"src\":\"b\",\"value\":\"2\"}]}";
        List<ConflictLedger.Conflict> conflicts = ConflictLedger.parse(json, rv);
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).label()).isEqualTo("phone");  // fallback: its own name
    }
}
