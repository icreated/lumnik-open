package io.lumnik.hub.workflow;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * #1588: a declared column the relation lacks (the order, a cited one, a declared field) is refused while the relation
 * exists; an absent relation is accepted and the report lists what it could not verify.
 */
@QuarkusTest
class WorkflowColumnsApplyIT {

    private static final String TENANT = "7311";
    private static final String TABLE = "connector.t_wf_order_apply_it";
    private static final String VIEW = "connector.v_wforderapplyit";

    @Inject SchemaCardStore cards;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static String yaml(String table, String order, String extra) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Workflow
            metadata: { name: wf-order-apply-it }
            spec:
              scope: demo
              provenance: declared
              on: { table: %s, column: statut, key: num%s }
            %s  states:
                - { code: A, initial: true }
                - { code: B, terminal: true }
              transitions:
                - { from: [A], to: B }
            """.formatted(table, order == null ? "" : ", order: " + order, extra);
    }

    private void sql(String s) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(s);
        }
    }

    @BeforeEach
    void seed() throws Exception {
        sql("DROP VIEW IF EXISTS " + VIEW);
        sql("DROP TABLE IF EXISTS " + TABLE);
        sql("CREATE TABLE " + TABLE + " (id bigserial PRIMARY KEY, tenant_id bigint, num text, statut text, "
            + "at_text text, at_tz timestamptz, flag boolean, due_on date)");
        sql("INSERT INTO " + TABLE + " (tenant_id, num, statut, at_text) VALUES "
            + "(" + TENANT + ", 'N1', 'A', '2026-10-26T10:00:00Z'), (" + TENANT + ", 'N1', 'B', 'bad'),"
            + "(" + TENANT + ", 'N2', 'A', NULL), (" + TENANT + ", NULL, 'A', 'bad')");   // no key: no document
        sql("CREATE VIEW " + VIEW + " AS SELECT num, statut, at_text, due_on FROM " + TABLE);
        sql("DELETE FROM core.workflow_manifest WHERE tenant_id = " + TENANT);
        cards.save(Long.parseLong(TENANT), "registry", new SchemaCard("demo", List.of()));
    }

    private static ValidatableResponse apply(String body) {
        return given().config(YamlBody.CONFIG).header("X-Tenant-ID", TENANT).header("X-User-ID", "1")
            .contentType("application/yaml").body(body).post("/api/workflows/apply").then();
    }

    @Test
    void an_order_the_relation_lacks_is_refused() {
        apply(yaml(TABLE, "nope", "")).statusCode(400)
            .body("error", containsString("spec.on.order nope is not a column of " + TABLE));
    }

    @Test
    void an_order_that_cannot_order_is_refused() {
        apply(yaml(TABLE, "flag", "")).statusCode(400)
            .body("error", containsString("spec.on.order flag is boolean"));
    }

    @Test
    void text_is_accepted_and_its_invalid_values_are_counted() {
        apply(yaml(TABLE, "at_text", "")).statusCode(200)
            .body("dataTruth.invalidOrderValues", equalTo(2))     // 'bad' and NULL
            .body("dataTruth.columnsUnverified", empty());
    }

    @Test
    void without_an_order_nothing_about_it_is_measured() {
        apply(yaml(TABLE, null, "")).statusCode(200)
            .body("dataTruth.invalidOrderValues", nullValue())
            .body("dataTruth.keysWithSeveralRows", nullValue());
    }

    @Test
    void an_absent_relation_is_accepted_and_says_what_was_not_verified() {
        apply(yaml("connector.t_wf_order_apply_missing_it", "at_text", "")).statusCode(200)
            .body("dataTruth.tableMissing", equalTo(true))
            .body("dataTruth.columnsUnverified", contains("at_text"));
    }

    /** The due sweep reads every row of a view without spec.on.order: the report says how many keys have several. */
    @Test
    void a_due_date_on_a_view_without_an_order_counts_the_keys_held_twice() {
        apply(yaml(VIEW, null, "  due: { column: due_on, states: [A] }\n"))
            .statusCode(200)
            .body("dataTruth.keysWithSeveralRows", equalTo(1));   // N1 holds two rows
    }

    @Test
    void a_cited_column_the_relation_lacks_is_refused_by_name() {
        apply(yaml(TABLE, "at_text", "  dormancy:\n    after: 1m\n    message: \"{num} ({laboratory}): {state}\"\n"))
            .statusCode(400)
            .body("error", containsString("spec.dormancy.message references {laboratory} — not a column of " + TABLE));
    }

    /** A typo of a reserved name ({sinse}) is told apart from a missing column: the refusal lists what may be named. */
    @Test
    void a_name_neither_a_column_nor_reserved_is_refused_with_the_reserved_names() {
        apply(yaml(TABLE, "at_text", "  dormancy:\n    after: 1m\n    message: \"{num} since {sinse}\"\n"))
            .statusCode(400)
            .body("error", containsString("{sinse} — not a column of " + TABLE
                + ", nor one of [doc_key, first_observed_at, since, state, state_label, threshold, workflow]"));
    }

    /** A due date's sentence and fields are checked as dormancy's are, under its own reserved names ({due} is one). */
    @Test
    void a_due_sentence_or_field_the_relation_lacks_is_refused_by_name() {
        apply(yaml(TABLE, "at_text", "  due: { column: due_on, states: [A], message: \"{doc_key} ({laboratory})\" }\n"))
            .statusCode(400)
            .body("error", containsString("spec.due.message references {laboratory} — not a column of " + TABLE));
        apply(yaml(TABLE, "at_text", "  due: { column: due_on, states: [A], fields: [owner_email] }\n"))
            .statusCode(400)
            .body("error", containsString("spec.due.fields names owner_email — not a column of " + TABLE));
        apply(yaml(TABLE, "at_text",
            "  due: { column: due_on, states: [A], message: \"{doc_key} due {due}: {statut}\", fields: [at_tz] }\n"))
            .statusCode(200);
    }

    @Test
    void a_declared_field_the_relation_lacks_is_refused_by_name() {
        apply(yaml(TABLE, "at_text", "  dormancy:\n    after: 1m\n    fields: [owner_email]\n")).statusCode(400)
            .body("error", containsString("spec.dormancy.fields names owner_email — not a column of " + TABLE));
    }

    @Test
    void cited_and_declared_columns_the_relation_has_are_accepted() {
        apply(yaml(TABLE, "at_text", "  dormancy:\n    after: 1m\n    message: \"{num}: {statut} since {since}\"\n"
            + "    fields: [at_tz]\n")).statusCode(200);
    }

    @Test
    void an_absent_relation_lists_every_column_it_could_not_verify() {
        apply(yaml("connector.t_wf_order_apply_missing_it", "at_text",
            "  dormancy:\n    after: 1m\n    message: \"{num} ({laboratory})\"\n    fields: [owner_email]\n"))
            .statusCode(200)
            .body("dataTruth.columnsUnverified", contains("at_text", "num", "laboratory", "owner_email"));
    }
}
