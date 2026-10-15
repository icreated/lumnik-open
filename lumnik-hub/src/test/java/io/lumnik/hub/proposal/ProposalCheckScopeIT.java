package io.lumnik.hub.proposal;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class ProposalCheckScopeIT {

    private static final long TENANT = 7411L;

    @Inject SchemaCardStore cards;

    private static String bearer(Set<String> roles) {
        return "Bearer " + NoBypassOidcProfile.token(roles, null, TENANT);
    }

    private String post(String token, String workflow) {
        RequestSpecification r = given().config(YamlBody.CONFIG).header("Authorization", token)
            .contentType("text/plain").body(DossierReaderTest.fixture("valid/minimal.md").getBytes(StandardCharsets.UTF_8));
        if (workflow != null) r = r.queryParam("workflow", workflow);
        return r.when().post("/api/proposal/check").then().statusCode(200).extract().asString();
    }

    @Test
    void a_bound_caller_outside_the_scope_gets_the_answer_an_absent_workflow_gets() {
        cards.save(TENANT, "registry", new SchemaCard("demo", List.of()));
        given().config(YamlBody.CONFIG).header("Authorization", bearer(Set.of("lm_integrator")))
            .contentType("application/yaml")
            .body(ProposalCheckIT.workflowYaml("wf-pc-scope", "connector.t_prop_check_scope", "statut", "num"))
            .when().post("/api/workflows/apply").then().statusCode(200);

        String bound = post(bearer(Set.of("lm_integrator", "scope:other")), "wf-pc-scope");
        String absent = post(bearer(Set.of("lm_integrator", "scope:other")), "wf-does-not-exist");
        String holder = post(bearer(Set.of("lm_integrator", "scope:demo")), "wf-pc-scope");

        assertThat(bound).isEqualTo(absent);                 // no leak of existence
        assertThat(holder).isNotEqualTo(absent);             // the holder of the scope does get its verdict
    }
}
