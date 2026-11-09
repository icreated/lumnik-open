package io.lumnik.hub.connector.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #1859: a SOAP service that resets its HTTP/2 stream after a complete Fault loses the Fault to the JDK client, which
 * negotiates HTTP/2 by ALPN. The connector's {@code http_version} makes the client speak what the integrator says; the
 * server tells which protocol it was spoken in. HTTPS, because only TLS negotiates a protocol. The JDK's
 * {@code keytool} makes the certificate at test time, as in {@link RestHttpClientTrustIT}.
 */
class RestHttpClientVersionIT {
    @TempDir static Path dir;
    static WireMockServer wm;
    static String pem;

    @BeforeAll static void start() throws Exception {
        Path store = dir.resolve("server.p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit");
        Path file = dir.resolve("server.pem");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(), "-storepass", "changeit",
            "-file", file.toString());
        pem = Files.readString(file);
        wm = new WireMockServer(options().dynamicPort().dynamicHttpsPort()
            .keystorePath(store.toString()).keystorePassword("changeit").keyManagerPassword("changeit")
            .keystoreType("PKCS12"));
        wm.start();
        wm.stubFor(post(urlPathEqualTo("/OrderService.svc")).willReturn(aResponse().withStatus(500).withBody("<Fault/>")));
    }

    @AfterAll static void stop() { if (wm != null) wm.stop(); }

    @BeforeEach void clear() { wm.resetRequests(); }

    private static RestHttpClient client() { return new RestHttpClient().trusting(pem); }

    private static RestRequest soap() {
        return RestRequest.post("https://localhost:" + wm.httpsPort() + "/OrderService.svc", "<Envelope/>");
    }

    private static List<String> protocols() {
        return wm.getAllServeEvents().stream().map(e -> e.getRequest().getProtocol()).toList();
    }

    @Test
    void without_a_declared_version_the_client_negotiates_http_2() throws Exception {
        client().execute(soap());

        assertEquals(List.of("HTTP/2.0"), protocols());
    }

    @Test
    void the_client_speaks_http_1_1_when_the_connector_says_so() throws Exception {
        RestResponse resp = client().speaking("1.1").execute(soap());

        assertEquals(500, resp.status());
        assertEquals("<Fault/>", resp.body());
        assertEquals(List.of("HTTP/1.1"), protocols());
    }

    @Test
    void the_version_survives_trusting_a_ca_in_either_order() throws Exception {
        new RestHttpClient().speaking("1.1").trusting(pem).execute(soap());

        assertEquals(List.of("HTTP/1.1"), protocols());
    }

    @Test
    void the_client_asks_for_http_2_when_the_connector_says_so() throws Exception {
        client().speaking("2").execute(soap());

        assertEquals(List.of("HTTP/2.0"), protocols());
    }

    @Test
    void a_version_the_jdk_client_has_no_name_for_is_refused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> client().speaking("3"));
        assertEquals("http_version must be 1.1 or 2", e.getMessage());
    }

    private static void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }
}
