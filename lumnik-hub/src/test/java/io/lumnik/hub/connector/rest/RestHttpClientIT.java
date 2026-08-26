package io.lumnik.hub.connector.rest;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 IT — no @QuarkusTest needed because RestHttpClient has
 * zero CDI dependencies beyond the Java 21 HttpClient it builds internally.
 */
class RestHttpClientIT {
    static WireMockServer wm;
    final RestHttpClient client = new RestHttpClient();

    @BeforeAll static void start() { wm = new WireMockServer(0); wm.start(); WireMock.configureFor("localhost", wm.port()); }
    @AfterAll  static void stop()  { wm.stop(); }

    @Test
    void executes_get_with_headers_and_query() throws Exception {
        stubFor(get(urlPathEqualTo("/v1/x"))
            .withQueryParam("limit", equalTo("10"))
            .withHeader("Authorization", equalTo("Bearer t"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        RestRequest req = RestRequest.get("http://localhost:" + wm.port() + "/v1/x")
            .withQuery("limit", "10").withHeader("Authorization", "Bearer t");
        RestResponse resp = client.execute(req);
        assertEquals(200, resp.status());
        assertTrue(resp.body().contains("\"ok\":true"));
    }
}
