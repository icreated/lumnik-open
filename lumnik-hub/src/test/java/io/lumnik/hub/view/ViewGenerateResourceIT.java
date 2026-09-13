package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;

@QuarkusTest
class ViewGenerateResourceIT {

    @Inject SchemaCardStore store;
    @InjectMock @Analytic ChatPort chatPort;

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
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_gen_demo "
           + "(customer_code text, name text, segment text)");
        exec("TRUNCATE connector.t_view_gen_demo");
        exec("INSERT INTO connector.t_view_gen_demo VALUES "
           + "('C-1','Alice','GOLD'), ('C-2','Bob','SILVER')");

        store.save(1L, "registry", new SchemaCard("gendesk", List.of(
            new SchemaCard.Table("connector.t_view_gen_demo", "Desk", List.of(
                new SchemaCard.Column("customer_code", "text", "Code"),
                new SchemaCard.Column("name", "text", "Name"),
                new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                        List.of("GOLD", "SILVER")))))));
    }

    private static final String GOOD =
        "{\"table\":\"connector.t_view_gen_demo\",\"title\":\"Gold desk\","
        + "\"columns\":[\"customer_code\",\"name\",\"segment\"],\"filters\":[\"segment=GOLD\"]}";

    @Test
    void generates_theManifest_asYaml_withTheSuggestedCommand() {
        Mockito.when(chatPort.chat(anyString())).thenReturn(GOOD);

        String yaml =
            given().header("X-Tenant-Id", "1").contentType("application/json")
                .body("{\"scope\":\"gendesk\",\"description\":\"les clients gold\"}")
            .when().post("/api/views/generate")
            .then().statusCode(200)
                .contentType(containsString("yaml"))
                .extract().asString();

        Assertions.assertTrue(yaml.contains("kind: \"View\"")
                || yaml.contains("kind: View"));
        // The header states what was asked and what the view narrows on, then the runnable
        // command — so the command is a line of the header, no longer the first line (#489).
        Assertions.assertEquals(List.of(
            "# asked for: les clients gold",
            "# kept: segment=GOLD — a condition of that description not listed here is NOT enforced",
            "# lm view -f gold_desk.yaml --filter 'segment=GOLD'"),
            yaml.lines().takeWhile(l -> l.startsWith("#")).toList());
    }

    @Test
    void theRealClientsAcceptHeader_isNotA406() {
        // The lm client asks for application/yaml — JAX-RS negotiation checks Accept against
        // the DECLARED @Produces before invoking the method (RestAssured's default */* hid
        // this: a class-level JSON-only @Produces 406'd the real client, caught live).
        Mockito.when(chatPort.chat(anyString())).thenReturn(GOOD);

        given().header("X-Tenant-Id", "1").contentType("application/json")
            .header("Accept", "application/yaml")
            .body("{\"scope\":\"gendesk\",\"description\":\"les clients gold\"}")
        .when().post("/api/views/generate")
        .then().statusCode(200)
            .contentType(containsString("yaml"));

        // And the error contract holds with the real client's full header: explicit q-values
        // (not mere order) make the upstream 401's Map entity negotiate to JSON.
        given().contentType("application/json")
            .header("Accept", "application/json, application/yaml;q=0.9")
            .body("{\"scope\":\"gendesk\",\"description\":\"les clients\"}")
        .when().post("/api/views/generate")
        .then().statusCode(401)
            .contentType(containsString("json"));
    }

    @Test
    void theGeneratedYaml_postedVerbatimToRender_returns200() {
        // THE loop closed: the generated artifact drives the existing surface untouched.
        Mockito.when(chatPort.chat(anyString())).thenReturn(GOOD);

        String yaml =
            given().header("X-Tenant-Id", "1").contentType("application/json")
                .body("{\"scope\":\"gendesk\",\"description\":\"les clients gold\"}")
            .when().post("/api/views/generate")
            .then().statusCode(200).extract().asString();

        given().header("X-Tenant-Id", "1").contentType("application/yaml")
            .config(YamlBody.CONFIG)
            .queryParam("filter", "segment=GOLD")
            .body(yaml)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("title", is("Gold desk"))
            .body("rows.customer_code", contains("C-1"));
    }

    @Test
    void nearMiss_isCorrectedByTheRetry() {
        Mockito.when(chatPort.chat(anyString()))
            .thenReturn(GOOD.replace("\"name\"", "\"customer_name\""), GOOD);

        given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"scope\":\"gendesk\",\"description\":\"les clients gold\"}")
        .when().post("/api/views/generate")
        .then().statusCode(200);

        Mockito.verify(chatPort, Mockito.times(2)).chat(anyString());
    }

    @Test
    void doubleFailure_isAnHonest400_withTheRawOutput() {
        String bad = GOOD.replace("\"name\"", "\"customer_name\"");
        Mockito.when(chatPort.chat(anyString())).thenReturn(bad, bad);

        given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"scope\":\"gendesk\",\"description\":\"les clients gold\"}")
        .when().post("/api/views/generate")
        .then().statusCode(400)
            .body("error", containsString("generation failed"))
            .body("error", containsString("customer_name"))
            .body("raw", containsString("customer_name"));
    }

    @Test
    void abstention_isAnHonest400() {
        Mockito.when(chatPort.chat(anyString()))
            .thenReturn("{\"abstain\":true,\"reason\":\"no order data in this scope\"}");

        given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"scope\":\"gendesk\",\"description\":\"les commandes de mars\"}")
        .when().post("/api/views/generate")
        .then().statusCode(400)
            .body("error", containsString("generation abstained"))
            .body("error", containsString("no order data"));
    }

    @Test
    void unknownScope_404() {
        given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"scope\":\"ghost\",\"description\":\"anything\"}")
        .when().post("/api/views/generate")
        .then().statusCode(404)
            .body("error", containsString("run discovery first"));
    }

    @Test
    void blankScopeOrDescription_400() {
        given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"scope\":\"gendesk\",\"description\":\"  \"}")
        .when().post("/api/views/generate")
        .then().statusCode(400)
            .body("error", containsString("required"));
    }

    @Test
    void withoutTenantInContext_401_asJson() {
        // The endpoint contract: errors are JSON (class-level @Produces) even for the 401
        // thrown upstream by RequireTenant; only success is application/yaml.
        given().contentType("application/json")
            .body("{\"scope\":\"gendesk\",\"description\":\"les clients\"}")
        .when().post("/api/views/generate")
        .then().statusCode(401)
            .contentType(containsString("json"));
    }
}
