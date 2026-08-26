package io.lumnik.hub.view;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ViewTypesTest {
    @Test
    void recordsHoldTheirFields() {
        var m = new ViewManifest("clients-desk", "clients", "connector.v_customer", "Clients",
                List.of("customer_code", "name"));
        assertThat(m.scope()).isEqualTo("clients");
        assertThat(m.columns()).containsExactly("customer_code", "name");

        var rv = new ResolvedView("Clients", "connector.v_customer",
                List.of(new ResolvedView.Column("name", "Name", "text")));
        assertThat(rv.title()).isEqualTo("Clients");
        assertThat(rv.table()).isEqualTo("connector.v_customer");
        assertThat(rv.columns().get(0).label()).isEqualTo("Name");
    }
}
