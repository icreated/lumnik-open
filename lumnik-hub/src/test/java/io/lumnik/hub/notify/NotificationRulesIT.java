package io.lumnik.hub.notify;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The email-rule surface (chantier C3): notification_rule existed since V24 with no write
 * path — EmailConsumer swept an eternally-empty table. Tenant-implicit path (the
 * InboxResource pattern), lm_admin; the resource filters by tenant explicitly.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRulesIT {

    private static final String TENANT = "7001";
    private static Long createdId;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    /** One row-event-emitting table for this tenant — a DynamicTableWriter target, not a hybrid one. */
    @BeforeEach
    void seedATableThatEmits() {
        exec("DELETE FROM core.notification_rule WHERE tenant_id = 7001 AND recipient = 'a@b.c'");
        exec("DELETE FROM connector.dynamic_table WHERE tenant_id = 7001 AND mapping_id = 'notif-demo'");
        exec("INSERT INTO connector.dynamic_table (tenant_id, mapping_id, table_schema, table_name, schema_inferred) "
            + "VALUES (7001, 'notif-demo', 'connector', 't_notif_demo', '{}'::jsonb)");
    }

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static RequestSpecification asAdmin(String tenant) {
        return given().header("X-Tenant-ID", tenant).header("X-User-ID", "1")
                      .header("Content-Type", "application/json");
    }

    @Test @Order(1)
    void create_rule_is_201() {
        createdId = ((Number) asAdmin(TENANT)
            .body("{\"eventPattern\":\"decision.*\",\"recipient\":\"adv@client.example\"}")
            .post("/api/notifications")
        .then()
            .statusCode(201)
            .body("eventPattern", equalTo("decision.*"))
            .body("recipient", equalTo("adv@client.example"))
            .body("template", equalTo("event-notification"))
            .body("enabled", is(true))
            .extract().path("id")).longValue();
    }

    @Test @Order(2)
    void unsafe_template_name_is_refused() {
        asAdmin(TENANT)
            .body("{\"eventPattern\":\"decision.*\",\"recipient\":\"a@b.c\",\"template\":\"../../etc/passwd\"}")
            .post("/api/notifications")
        .then().statusCode(400)
            .body("error", containsString("template"));
    }

    @Test @Order(3)
    void list_is_tenant_bound() {
        asAdmin(TENANT).get("/api/notifications")
        .then().statusCode(200)
            .body("find { it.recipient == 'adv@client.example' }.eventPattern", equalTo("decision.*"));
        asAdmin("7002").get("/api/notifications")
        .then().statusCode(200)
            .body("find { it.recipient == 'adv@client.example' }", nullValue());
    }

    // ── entity_filter: a filter no event can carry is refused (#504) ────────────

    @Test @Order(3)
    void entity_filter_naming_a_table_that_emits_is_201() {
        asAdmin(TENANT)
            .body("{\"eventPattern\":\"data.row.changed\",\"recipient\":\"a@b.c\","
                + "\"entityFilter\":\"connector.t_notif_demo\"}")
            .post("/api/notifications")
        .then().statusCode(201)
            .body("entityFilter", equalTo("connector.t_notif_demo"));
    }

    @Test @Order(3)
    void entity_filter_no_event_can_carry_is_refused() {
        // The entity of a data.row.changed is the table FQN DynamicTableWriter stages, so
        // `t_notif_demo` and `customers` match nothing — for ever, silently, and RowEventGate
        // consults the same column, so the event is never even staged.
        for (String filter : new String[] {"t_notif_demo", "connector.t_absent", "customers"}) {
            asAdmin(TENANT)
                .body("{\"eventPattern\":\"data.row.changed\",\"recipient\":\"a@b.c\","
                    + "\"entityFilter\":\"" + filter + "\"}")
                .post("/api/notifications")
            .then().statusCode(400)
                .body("error", containsString(filter));
        }
    }

    @Test @Order(3)
    void entity_filter_on_another_event_family_is_left_alone() {
        // The entity of a decision.dormant.* is the workflow's watched table, which may be a fused
        // view — a family this door cannot judge, so it does not pretend to. Refusing here would
        // have been a false refusal of a legitimate subscription.
        asAdmin(TENANT)
            .body("{\"eventPattern\":\"decision.dormant.commandes\",\"recipient\":\"a@b.c\","
                + "\"entityFilter\":\"connector.v_commande\"}")
            .post("/api/notifications")
        .then().statusCode(201)
            .body("entityFilter", equalTo("connector.v_commande"));
    }

    @Test @Order(3)
    void without_a_tenant_a_malformed_body_is_still_401_not_400() {
        // The order is the assertion: an unauthenticated caller must not be able to tell a
        // malformed body from a well-formed one. Copilot caught this on #510 — the same rule the
        // schema-card door and both process write doors already follow.
        given().header("Content-Type", "application/json").body("{}")
            .post("/api/notifications")
        .then().statusCode(401);
    }

    @Test @Order(4)
    void delete_is_204_and_removes() {
        asAdmin(TENANT).delete("/api/notifications/" + createdId).then().statusCode(204);
        asAdmin(TENANT).get("/api/notifications")
        .then().body("find { it.recipient == 'adv@client.example' }", nullValue());
    }
}
