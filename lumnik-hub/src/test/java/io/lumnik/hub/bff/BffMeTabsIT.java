package io.lumnik.hub.bff;

import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact offer and the exact active module list, per packaging, selected by the edition the hub
 * itself reports: an extra tab, a lost {@code chat}, or an inactive module leaking back into
 * {@code modules} must each fail here. Runs in every packaging.
 */
@QuarkusTest
class BffMeTabsIT {

    @Test
    void meOffersExactlyWhatThisBuildCarries() {
        var me = given().headers(TestIdentity.tenantAndUser(1))
            .when().get("/api/bff/v1/me")
            .then().statusCode(200).extract().jsonPath();

        List<String> tabs = me.getList("tabs");
        List<String> modules = me.getList("modules.id");

        // A Search build (-Psearch, -Psearch-cluster; the release lanes run the first) adds its provider
        // module and the `search` tab, and nothing else: the extras are pinned, not tolerated.
        List<String> searchModules = modules.stream().filter(id -> id.startsWith("search_")).toList();
        assertThat(searchModules).hasSizeLessThanOrEqualTo(1);
        List<String> base = new ArrayList<>(modules);
        base.removeAll(searchModules);

        boolean pro = "commercial".equals(me.getString("edition"));
        List<String> expectedTabs = new ArrayList<>(pro ? List.of("chat", "fiches", "inbox") : List.of("fiches", "inbox"));
        if (!searchModules.isEmpty()) expectedTabs.add("search");

        assertThat(tabs).containsExactlyElementsOf(expectedTabs);
        if (pro) {
            assertThat(base).containsExactlyInAnyOrder("core", "identity", "rag", "llm");
        } else {
            assertThat(base).containsExactlyInAnyOrder("core", "identity");
        }
    }
}
