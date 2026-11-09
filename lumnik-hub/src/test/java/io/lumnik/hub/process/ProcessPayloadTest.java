package io.lumnik.hub.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessPayloadTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buildsValidJsonForAMessageWithSpecialCharacters() throws Exception {
        // a backslash, a newline, and a quote — all of which break naive quote-only escaping
        String msg = "Route to warehouse\nPriority: \"high\" \\ urgent";
        String payload = ProcessConsumer.buildPayload(json, msg, List.of(), "gold-arrive", "connector.t_x", "hash-1", Map.of());

        JsonNode n = json.readTree(payload); // must not throw — proves valid JSON
        assertThat(n.get("message").asText()).isEqualTo(msg);   // round-trips intact
        assertThat(n.get("process").asText()).isEqualTo("gold-arrive");
        assertThat(n.get("entity").asText()).isEqualTo("connector.t_x");
        assertThat(n.get("entity_key").asText()).isEqualTo("hash-1");
        assertThat(n.has("fields")).isFalse();   // placeholder-free → payload shape unchanged
    }

    @Test
    void theDecisionKeyIsTheDeclaredColumn_elseTheRowHash() {
        Map<String, Object> row = new HashMap<>();
        row.put("sample", "analysisrequest-24");
        row.put("lot", BigDecimal.valueOf(240000, 4));
        row.put("blank", "  ");
        row.put("empty", null);
        assertThat(ProcessConsumer.decisionKey("sample", row, "hash-1")).isEqualTo("analysisrequest-24");
        assertThat(ProcessConsumer.decisionKey("lot", row, "hash-1")).isEqualTo("24");   // rendered like a {col}
        assertThat(ProcessConsumer.decisionKey(null, row, "hash-1")).isEqualTo("hash-1");
        assertThat(ProcessConsumer.decisionKey("blank", row, "hash-1")).isEqualTo("hash-1");
        assertThat(ProcessConsumer.decisionKey("empty", row, "hash-1")).isEqualTo("hash-1");
        row.put("sample-id", "S-7");   // a name no {placeholder} can spell: the value still keys it
        assertThat(ProcessConsumer.decisionKey("sample-id", row, "hash-1")).isEqualTo("S-7");
    }

    @Test
    void toleratesANullEntity() throws Exception {
        String payload = ProcessConsumer.buildPayload(json, "msg", List.of(), "proc", null, "h", Map.of());
        assertThat(json.readTree(payload).get("entity").isNull()).isTrue();  // JSON null, not the text "null"
    }

    @Test
    void substitutesPlaceholders_andCarriesReferencedFieldsOnly() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("cname", "Claire Martin");
        row.put("ville", "Lyon");
        row.put("email", "claire@x.fr");   // NOT referenced — must not leak into fields
        String payload = ProcessConsumer.buildPayload(json,
            "Nouveau client {cname} à {ville}", List.of(), "lyon", "connector.t_cust_csv", "h9", row);

        JsonNode n = json.readTree(payload);
        assertThat(n.get("message").asText()).isEqualTo("Nouveau client Claire Martin à Lyon");
        assertThat(n.get("fields").get("cname").asText()).isEqualTo("Claire Martin");
        assertThat(n.get("fields").get("ville").asText()).isEqualTo("Lyon");
        assertThat(n.get("fields").has("email")).isFalse();
        assertThat(n.get("fields").size()).isEqualTo(2);
    }

    @Test
    void aNullReferencedValueRendersEmpty_andIsJsonNullInFields() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("cname", null);
        String payload = ProcessConsumer.buildPayload(json,
            "Client {cname}!", List.of(), "p", "e", "k", row);

        JsonNode n = json.readTree(payload);
        assertThat(n.get("message").asText()).isEqualTo("Client !");
        assertThat(n.get("fields").get("cname").isNull()).isTrue();
    }

    /** #1365: a declared field rides along without being written into the sentence. */
    @Test
    void declaredFieldsRideAlongWithoutEnteringTheMessage() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("supplier_id", "SUP-0042");
        row.put("iban", "FR7630006000011234567890189");
        String payload = ProcessConsumer.buildPayload(json, "Bank account observed for {supplier_id}",
            List.of("iban", "supplier_id"), "p", "e", "k", row);

        JsonNode n = json.readTree(payload);
        assertThat(n.get("message").asText()).isEqualTo("Bank account observed for SUP-0042");
        assertThat(n.get("fields").get("iban").asText()).isEqualTo("FR7630006000011234567890189");
        assertThat(n.get("fields").size()).isEqualTo(2);   // supplier_id once
    }

    /** #1314: a timestamp field is its UTC instant, microseconds kept — the text the message shows. */
    @Test
    void aTimestampFieldIsItsInstant_asInTheMessage() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("due", Timestamp.from(Instant.parse("2026-07-01T00:00:00.000500Z")));
        String payload = ProcessConsumer.buildPayload(json, "Due {due}", List.of(), "p", "e", "k", row);

        JsonNode n = json.readTree(payload);
        assertThat(n.get("message").asText()).isEqualTo("Due 2026-07-01T00:00:00.000500Z");
        assertThat(n.get("fields").get("due").asText()).isEqualTo("2026-07-01T00:00:00.000500Z");
    }
}
