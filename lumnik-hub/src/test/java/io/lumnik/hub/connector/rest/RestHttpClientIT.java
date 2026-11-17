package io.lumnik.hub.connector.rest;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.outbound.OutboundAddress;
import io.lumnik.hub.testsupport.TestOutbound;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 IT — no @QuarkusTest needed because RestHttpClient has
 * zero CDI dependencies beyond the Java 21 HttpClient it builds internally.
 */
class RestHttpClientIT {
    static WireMockServer wm;
    final RestHttpClient client = new RestHttpClient(TestOutbound.ADDRESSES);

    @BeforeAll static void start() { wm = new WireMockServer(0); wm.start(); }
    @AfterAll  static void stop()  { wm.stop(); }

    @Test
    void executes_get_with_headers_and_query() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/v1/x"))
            .withQueryParam("limit", equalTo("10"))
            .withHeader("Authorization", equalTo("Bearer t"))
            .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        RestRequest req = RestRequest.get("http://localhost:" + wm.port() + "/v1/x")
            .withQuery("limit", "10").withHeader("Authorization", "Bearer t");
        RestResponse resp = client.execute(req);
        assertEquals(200, resp.status());
        assertTrue(resp.body().contains("\"ok\":true"));
    }

    /** A loopback source on a hub that refuses loopback (#2002): refused before anything is sent. */
    @Test
    void a_refused_address_is_never_sent_to() {
        wm.stubFor(get("/v1/x").willReturn(ok("{}")));
        wm.resetRequests();
        RestHttpClient production = new RestHttpClient(new OutboundAddress(false, true));
        ConfigValidationException refused = assertThrows(ConfigValidationException.class,
            () -> production.execute(RestRequest.get("http://127.0.0.1:" + wm.port() + "/v1/x")));
        assertTrue(refused.getMessage().contains("loopback"), refused.getMessage());
        assertEquals(0, wm.getAllServeEvents().size());
    }

    /** A body past the cap fails the exchange, not the heap (#2002); a body at the cap is read whole. */
    @Test
    void a_body_past_the_cap_is_refused_and_one_at_it_is_read() throws Exception {
        wm.stubFor(get("/big").willReturn(ok("x".repeat(1025))));
        wm.stubFor(get("/fits").willReturn(ok("x".repeat(1024))));
        RestHttpClient capped = RestHttpClient.cappedAt(TestOutbound.ADDRESSES, 1024);
        ConnectorException tooLarge = assertThrows(ConnectorException.class,
            () -> capped.execute(RestRequest.get("http://localhost:" + wm.port() + "/big")));
        assertTrue(tooLarge.getMessage().contains("larger than"), tooLarge.getMessage());
        assertEquals(1024, capped.execute(RestRequest.get("http://localhost:" + wm.port() + "/fits")).body().length());
    }

    /**
     * #2033: refusing a body cancels its stream, and over HTTP/2 the cancel can end the exchange with its own error
     * ("Stream 1 cancelled") before the refusal reaches it. The hub still answers with its own sentence. A race, so
     * asked many times, each on a new stream of an HTTP/2 connection. A new client every {@code PER_CONNECTION}
     * attempts (#2053): WireMock's Jetty resets a connection that cancels about 128 streams a second (its HTTP/2
     * rapid-reset guard), which ended attempt 127 in "Connection reset" whenever the loop ran fast.
     */
    /** Under the server's rapid-reset threshold (about 128 cancelled streams a second on one connection). */
    private static final int PER_CONNECTION = 64;

    @Test
    void a_body_past_the_cap_is_refused_whatever_the_cancelled_stream_reports() throws Exception {
        wm.stubFor(get("/big").willReturn(ok("x".repeat(64 * 1024))));
        try (HttpClient probe = HttpClient.newHttpClient()) {
            assertEquals(HttpClient.Version.HTTP_2, probe.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + wm.port() + "/big")).build(), HttpResponse.BodyHandlers.discarding())
                .version(), "precondition: the source speaks HTTP/2, where the race lives");
        }
        RestHttpClient capped = null;
        for (int i = 0; i < 200; i++) {
            if (i % PER_CONNECTION == 0) capped = RestHttpClient.cappedAt(TestOutbound.ADDRESSES, 1024);
            RestHttpClient client = capped;
            ConnectorException tooLarge = assertThrows(ConnectorException.class,
                () -> client.execute(RestRequest.get("http://localhost:" + wm.port() + "/big")), "attempt " + i);
            assertTrue(tooLarge.getMessage().contains("larger than"), tooLarge.getMessage());
        }
    }

    /**
     * A Content-Length past the cap is refused at the first buffer, not read to the cap. The body
     * here is shorter than it declares: read on, the exchange would end in an I/O failure instead.
     */
    @Test
    void a_declared_length_past_the_cap_is_refused() {
        wm.stubFor(get("/declared").willReturn(ok("x".repeat(10)).withHeader("Content-Length", "2048")));
        RestHttpClient capped = RestHttpClient.cappedAt(TestOutbound.ADDRESSES, 1024);
        ConnectorException tooLarge = assertThrows(ConnectorException.class,
            () -> capped.execute(RestRequest.get("http://localhost:" + wm.port() + "/declared")));
        assertTrue(tooLarge.getMessage().contains("larger than"), tooLarge.getMessage());
    }
}
