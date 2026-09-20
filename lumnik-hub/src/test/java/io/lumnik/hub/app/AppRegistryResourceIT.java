package io.lumnik.hub.app;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/** The read the PWA discovers its own identity from. */
@QuarkusTest
class AppRegistryResourceIT {

    @Test
    void listsTheSeededAppsForAnUnscopedCaller() {
        given().header("X-Tenant-ID", "1")
            .when().get("/api/apps")
            .then().statusCode(200)
            .body("name", hasItems("clients", "stock"))
            .body("find { it.name == 'clients' }.title", equalTo("Clients"))
            .body("find { it.name == 'clients' }.icon", equalTo("clients"))
            .body("find { it.name == 'clients' }.accentColor", equalTo("#8b7ff0"))
            .body("find { it.name == 'clients' }.default", equalTo(true))
            .body("find { it.name == 'stock' }.default", equalTo(false));
    }

    @Test
    void neverServesTheRawManifest() {
        given().header("X-Tenant-ID", "1")
            .when().get("/api/apps")
            .then().statusCode(200)
            .body("[0]", not(hasKey("yamlText")))
            .body("[0]", not(hasKey("yaml_text")));
    }

    @Test
    void servesOneAppByName() {
        given().header("X-Tenant-ID", "1")
            .when().get("/api/apps/clients")
            .then().statusCode(200)
            .body("name", equalTo("clients"))
            .body("backgroundColor", equalTo("#14121f"));
    }

    @Test
    void refusesAnUnknownNameInTheExactShapeThePwaBranchesOn() {
        given().header("X-Tenant-ID", "1")
            .when().get("/api/apps/lumilait-nope")
            .then().statusCode(404)
            .body("error", equalTo("no app named 'lumilait-nope'"))
            .body("error", containsString("no app named"));
    }
}
